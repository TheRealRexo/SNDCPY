#!/usr/bin/env python3
"""
SNDCPY Linux/macOS GUI Client — supports secure (v2) handshakes.

Usage:
    python3 sndcpy_gui_client.py

Requirements:
    pip install PyQt6 cryptography
"""

import sys
import os
import socket
import threading
import time
import base64
import struct
from typing import Optional, Dict, List, Tuple

from PyQt6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLineEdit, QPushButton, QLabel, QTextEdit, QFrame, QScrollArea
)
from PyQt6.QtCore import Qt, QTimer, QThread, pyqtSignal, QObject
from PyQt6.QtGui import QFont, QPalette, QColor

try:
    from cryptography.hazmat.primitives.asymmetric.ec import SECP256R1
    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.asymmetric.ec import EllipticCurvePublicKey
    CRYPTO_AVAILABLE = True
except ImportError:
    CRYPTO_AVAILABLE = False
    print("Warning: cryptography not available. Install with: pip install cryptography")

DEFAULT_PORT = 27183
BROADCAST_PORT = 27184
BROADCAST_PREFIX = "SNDCPYv1"


def generate_ec_keypair():
    """Generate an ECDH P-256 key pair and return X963-encoded public key."""
    if not CRYPTO_AVAILABLE:
        raise RuntimeError("cryptography not available")
    from cryptography.hazmat.primitives.asymmetric import ec
    private_key = ec.generate_private_key(SECP256R1())
    public_key = private_key.public_key()
    # Encode as X963 (0x04 || x || y, 65 bytes), base64-encoded
    x = public_key.public_numbers().x.to_bytes(32, 'big')
    y = public_key.public_numbers().y.to_bytes(32, 'big')
    x963 = b'\x04' + x + y
    return base64.b64encode(x963).decode(), private_key


def random_nonce(length: int = 16) -> bytes:
    return os.urandom(length)


def decode_bytes_b64(encoded: str) -> bytes:
    return base64.b64decode(encoded)


class DiscoveryWorker(QObject):
    """Discovers SNDCPY servers on the LAN via UDP beacons."""
    server_found = pyqtSignal(str, int, str)  # ip, port, name
    
    def __init__(self):
        super().__init__()
        self.running = False
    
    def start(self):
        self.running = True
        threading.Thread(target=self._listen, daemon=True).start()
    
    def stop(self):
        self.running = False
    
    def _listen(self):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("", BROADCAST_PORT))
            sock.settimeout(1.0)
            
            discovered = set()
            while self.running:
                try:
                    data, addr = sock.recvfrom(512)
                    text = data.decode(errors="replace")
                    if text.startswith(BROADCAST_PREFIX):
                        ip = addr[0]
                        if ip not in discovered:
                            discovered.add(ip)
                            # Parse beacon
                            for token in text.split(" "):
                                if "=" in token:
                                    k, v = token.split("=", 1)
                                    if k == "port":
                                        port = int(v)
                                    elif k == "name":
                                        name = v
                            else:
                                port = DEFAULT_PORT
                                name = "SNDCPY"
                            self.server_found.emit(ip, port, name)
                except socket.timeout:
                    continue
        except OSError:
            pass


class ClientWorker(QObject):
    """Handles the TCP connection to the SNDCPY server."""
    state_changed = pyqtSignal(str, str)  # state, detail
    message = pyqtSignal(str)
    pairing_required = pyqtSignal()
    connected = pyqtSignal()
    error = pyqtSignal(str)
    
    def __init__(self):
        super().__init__()
        self.sock: Optional[socket.socket] = None
        self.session_key = None
        self.running = False
        self.client_public_b64 = ""
        self.client_nonce_b64 = ""
        self.client_private = None
    
    def connect(self, host: str, port: int):
        threading.Thread(target=self._connect, args=(host, port), daemon=True).start()
    
    def _connect(self, host: str, port: int):
        try:
            self.state_changed.emit("connecting", f"Connecting to {host}:{port}...")
            
            # Generate keys
            if CRYPTO_AVAILABLE:
                self.client_public_b64, self.client_private = generate_ec_keypair()
                self.client_nonce_b64 = base64.b64encode(random_nonce()).decode()
            else:
                self.client_public_b64 = "BAAAAAEAAAAwaW1hZ2UvdXL/ng=="
                self.client_nonce_b64 = "AAAAAA=="
            
            self.sock = socket.create_connection((host, port), timeout=8)
            self.sock.settimeout(5.0)
            
            # Send HELLO
            hello = f"HELLO SNDCPY {self.client_public_b64} {self.client_nonce_b64}\n".encode()
            self.sock.sendall(hello)
            
            # Read response
            response = self._read_line()
            self.message.emit(f"Server: {response}")
            
            parts = response.strip().split()
            if len(parts) < 3:
                self.error.emit("Invalid handshake response")
                return
            
            status = parts[0]
            if status == "PAIRING_REQUIRED":
                self.server_public_b64 = parts[1]
                self.server_nonce_b64 = parts[2]
                self.pairing_required.emit()
                self.state_changed.emit("pairing", "Enter pairing code")
            elif status == "SECURE":
                self.server_public_b64 = parts[1]
                self.server_nonce_b64 = parts[2]
                self._derive_session_key()
                self.state_changed.emit("connected", "Connected")
                self.connected.emit()
                self._read_loop()
            else:
                self.error.emit(f"Handshake failed: {status}")
                
        except Exception as e:
            self.error.emit(str(e))
    
    def submit_pairing_code(self, code: str):
        if self.sock:
            try:
                self.sock.sendall(f"PAIR {code}\n".encode())
                response = self._read_line()
                if response.strip() == "PAIRED":
                    self.message.emit("Paired successfully")
                    self._derive_session_key()
                    self.state_changed.emit("connected", "Connected")
                    self.connected.emit()
                    self._read_loop()
                else:
                    self.error.emit(f"Pairing failed: {response}")
            except Exception as e:
                self.error.emit(str(e))
    
    def _derive_session_key(self):
        if not CRYPTO_AVAILABLE:
            return
        try:
            server_public_der = decode_bytes_b64(self.server_public_b64)
            server_key = EllipticCurvePublicKey.from_encoded_point(SECP256R1(), server_public_der)
            client_key = self.client_private.public_key()
            shared = client_key.exchange(ec.ECDH(), server_key)
            hkdf = HKDF(
                algorithm=hashes.SHA256(),
                length=32,
                salt=decode_bytes_b64(self.client_nonce_b64) + decode_bytes_b64(self.server_nonce_b64),
                info=b"SNDCPY-SESSION-KEY-v1"
            )
            self.session_key = hkdf.derive(shared)
            self.message.emit("Session key derived")
        except Exception as e:
            self.error.emit(f"Key derivation failed: {e}")
    
    def _read_line(self) -> str:
        buf = b""
        while True:
            chunk = self.sock.recv(1)
            if not chunk:
                break
            buf += chunk
            if chunk == b"\n":
                break
        return buf.decode(errors="replace").strip()
    
    def _read_loop(self):
        while self.running and self.sock:
            try:
                # Read 4-byte length
                len_data = self.sock.recv(4)
                if not len_data or len(len_data) < 4:
                    break
                length = struct.unpack(">I", len_data)[0]
                
                # Read body
                body = b""
                while len(body) < length:
                    chunk = self.sock.recv(min(4096, length - len(body)))
                    if not chunk:
                        break
                    body += chunk
                
                if len(body) < length:
                    break
                
                # Decrypt if we have a session key
                if self.session_key and len(body) > 12:
                    try:
                        aesgcm = AESGCM(self.session_key)
                        plaintext = aesgcm.decrypt(body[:12], body[12:], None)
                        # Process envelope
                        if len(plaintext) >= 9:
                            channel = plaintext[0]
                            pts_us = struct.unpack(">q", plaintext[1:9])[0]
                            payload = plaintext[9:]
                            if channel == 0x02:  # VIDEO_H264
                                # Video frame received (would be decoded by video player)
                                pass
                    except Exception:
                        pass
            except socket.timeout:
                continue
            except Exception as e:
                self.error.emit(f"Read error: {e}")
                break
    
    def send_control(self, command: str):
        if self.sock and self.session_key:
            try:
                # Build envelope
                pts_us = int(time.time() * 1_000_000)
                envelope = struct.pack("bq", 0x01, pts_us) + command.encode('utf-8')
                
                # Encrypt
                aesgcm = AESGCM(self.session_key)
                iv = os.urandom(12)
                ciphertext = aesgcm.encrypt(iv, envelope, None)
                
                # Send with length prefix
                frame = struct.pack(">I", len(iv) + len(ciphertext)) + iv + ciphertext
                self.sock.sendall(frame)
            except Exception as e:
                self.error.emit(f"Send error: {e}")
    
    def disconnect(self):
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except:
                pass
            self.sock = None
        self.session_key = None
        self.state_changed.emit("disconnected", "Disconnected")


class SNDCPYWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("SNDCPY Client")
        self.setMinimumSize(800, 600)
        
        # Apply dark theme
        self._apply_dark_theme()
        
        # Worker
        self.worker = ClientWorker()
        self.worker.state_changed.connect(self._on_state_changed)
        self.worker.message.connect(self._on_message)
        self.worker.pairing_required.connect(self._on_pairing_required)
        self.worker.connected.connect(self._on_connected)
        self.worker.error.connect(self._on_error)
        
        # Discovery
        self.discovery = DiscoveryWorker()
        self.discovery.server_found.connect(self._on_server_found)
        
        # UI
        self._setup_ui()
        
        # Start discovery
        self.discovery.start()
    
    def _apply_dark_theme(self):
        dark_palette = QPalette()
        dark_palette.setColor(QPalette.ColorRole.Window, QColor(53, 53, 53))
        dark_palette.setColor(QPalette.ColorRole.WindowText, Qt.GlobalColor.white)
        dark_palette.setColor(QPalette.ColorRole.Base, QColor(25, 25, 25))
        dark_palette.setColor(QPalette.ColorRole.AlternateBase, QColor(53, 53, 53))
        dark_palette.setColor(QPalette.ColorRole.ToolTipBase, Qt.GlobalColor.white)
        dark_palette.setColor(QPalette.ColorRole.ToolTipText, Qt.GlobalColor.white)
        dark_palette.setColor(QPalette.ColorRole.Text, Qt.GlobalColor.white)
        dark_palette.setColor(QPalette.ColorRole.Button, QColor(53, 53, 53))
        dark_palette.setColor(QPalette.ColorRole.ButtonText, Qt.GlobalColor.white)
        dark_palette.setColor(QPalette.ColorRole.BrightText, Qt.GlobalColor.red)
        dark_palette.setColor(QPalette.ColorRole.Link, QColor(42, 130, 218))
        dark_palette.setColor(QPalette.ColorRole.Highlight, QColor(42, 130, 218))
        dark_palette.setColor(QPalette.ColorRole.HighlightedText, Qt.GlobalColor.black)
        QApplication.instance().setPalette(dark_palette)
    
    def _setup_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        layout = QVBoxLayout(central)
        layout.setSpacing(8)
        
        # Pairing code row (always visible)
        pairing_row = QHBoxLayout()
        pairing_label = QLabel("Pairing code:")
        pairing_label.setFont(QFont("monospace", 14, QFont.Weight.Bold))
        self.pairing_code_edit = QLineEdit()
        self.pairing_code_edit.setPlaceholderText("Enter 6-digit code")
        self.pairing_code_edit.setInputMethodHints(Qt.InputMethodHint.ImhDigitsOnly)
        self.pairing_code_edit.setMaximumWidth(150)
        self.pairing_code_edit.setFont(QFont("monospace", 14))
        self.pairing_code_edit.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.send_pairing_btn = QPushButton("Send Code")
        self.send_pairing_btn.clicked.connect(self._send_pairing_code)
        pairing_row.addWidget(pairing_label)
        pairing_row.addWidget(self.pairing_code_edit)
        pairing_row.addWidget(self.send_pairing_btn)
        layout.addLayout(pairing_row)
        
        # Connection row
        conn_row = QHBoxLayout()
        self.host_edit = QLineEdit()
        self.host_edit.setPlaceholderText("Server IP")
        self.host_edit.setText("192.168.1.42")
        self.port_edit = QLineEdit()
        self.port_edit.setPlaceholderText("Port")
        self.port_edit.setText("27183")
        self.port_edit.setMaximumWidth(80)
        self.connect_btn = QPushButton("Connect")
        self.connect_btn.clicked.connect(self._connect)
        conn_row.addWidget(QLabel("Server:"))
        conn_row.addWidget(self.host_edit)
        conn_row.addWidget(self.port_edit)
        conn_row.addWidget(self.connect_btn)
        layout.addLayout(conn_row)
        
        # Discovered servers
        self.discovered_label = QLabel("Discovered servers: (listening...)")
        self.discovered_label.setStyleSheet("color: gray; font-size: 12px;")
        layout.addWidget(self.discovered_label)
        
        self.discovered_frame = QFrame()
        self.discovered_frame.setFrameStyle(QFrame.Shape.StyledPanel)
        discovered_layout = QVBoxLayout(self.discovered_frame)
        discovered_layout.setSpacing(4)
        self.discovered_buttons: List[Tuple[str, int, QPushButton]] = []
        layout.addWidget(self.discovered_frame)
        
        # Status
        self.status_label = QLabel("Disconnected")
        self.status_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.status_label.setStyleSheet("font-size: 14px; padding: 8px;")
        layout.addWidget(self.status_label)
        
        # Control buttons
        ctrl_row = QHBoxLayout()
        self.home_btn = QPushButton("Home")
        self.back_btn = QPushButton("Back")
        self.recents_btn = QPushButton("Recents")
        self.home_btn.setEnabled(False)
        self.back_btn.setEnabled(False)
        self.recents_btn.setEnabled(False)
        self.home_btn.clicked.connect(lambda: self._send_control("HOME"))
        self.back_btn.clicked.connect(lambda: self._send_control("BACK"))
        self.recents_btn.clicked.connect(lambda: self._send_control("RECENTS"))
        ctrl_row.addWidget(self.home_btn)
        ctrl_row.addWidget(self.back_btn)
        ctrl_row.addWidget(self.recents_btn)
        layout.addLayout(ctrl_row)
        
        # Status log
        log_label = QLabel("Connection log:")
        log_label.setStyleSheet("color: gray; font-size: 11px;")
        layout.addWidget(log_label)
        
        self.log_edit = QTextEdit()
        self.log_edit.setReadOnly(True)
        self.log_edit.setMaximumHeight(120)
        self.log_edit.setFont(QFont("monospace", 10))
        layout.addWidget(self.log_edit)
    
    def _on_server_found(self, ip: str, port: int, name: str):
        btn = QPushButton(f"{name} @ {ip}:{port}")
        btn.clicked.connect(lambda: self._use_server(ip, port))
        layout = self.discovered_frame.layout()
        layout.addWidget(btn)
        self.discovered_buttons.append((ip, port, btn))
        self.discovered_label.setText(f"Discovered servers: ({len(self.discovered_buttons)} found)")
    
    def _use_server(self, ip: str, port: int):
        self.host_edit.setText(ip)
        self.port_edit.setText(str(port))
    
    def _connect(self):
        host = self.host_edit.text().strip()
        port = int(self.port_edit.text().strip() or DEFAULT_PORT)
        self.worker.connect(host, port)
    
    def _send_pairing_code(self):
        code = self.pairing_code_edit.text().strip()
        if code:
            self.worker.submit_pairing_code(code)
            self.pairing_code_edit.clear()
    
    def _send_control(self, command: str):
        self.worker.send_control(command)
        self._log(f">> {command}")
    
    def _on_state_changed(self, state: str, detail: str):
        self.status_label.setText(detail)
        if state == "connected":
            self.connect_btn.setText("Disconnect")
            self.home_btn.setEnabled(True)
            self.back_btn.setEnabled(True)
            self.recents_btn.setEnabled(True)
        elif state == "disconnected":
            self.connect_btn.setText("Connect")
            self.home_btn.setEnabled(False)
            self.back_btn.setEnabled(False)
            self.recents_btn.setEnabled(False)
    
    def _on_message(self, msg: str):
        self._log(msg)
    
    def _on_pairing_required(self):
        # Show alert with pairing code entry via QInputDialog (reverted from inline text box)
        code, ok = QInputDialog.getText(
            self, "Pairing Required",
            "Enter the 6-digit pairing code:",
            QLineEdit.EchoMode.Normal,
            ""
        )
        if ok and code.strip():
            self.submit_pairing_code(code.strip())
        self._log("Server requires pairing code - %s", code if ok else "cancelled")
    
    def _on_connected(self):
        self._log("Connected and secured")
    
    def _on_error(self, msg: str):
        self._log(f"ERROR: {msg}")
    
    def _log(self, msg: str):
        timestamp = time.strftime("%H:%M:%S")
        self.log_edit.append(f"[{timestamp}] {msg}")

    def _on_method_toggled(self, checked: bool, method: str):
        """Handle pairing method selection toggle."""
        # Hide all method-specific rows
        self.pairing_row.setVisible(False)
        self.bluetooth_row.setVisible(False)

        # Show the appropriate row based on selected method
        if method == "Code":
            self.pairing_row.setVisible(True)
            self._log("Pairing method: Code (enter 6-digit code)")
        elif method == "Bluetooth":
            self.bluetooth_row.setVisible(True)
            self._log("Pairing method: Bluetooth (mutual confirmation)")
        elif method == "USB":
            self._log("Pairing method: USB (desktop only - connect cable)")

    def _confirm_bluetooth(self):
        """Handle Bluetooth-style mutual confirmation."""
        code = self.bluetooth_confirm_edit.text().strip()
        if code and len(code) == 6 and code.isdigit():
            # In Bluetooth mode, send our code for server-side confirmation
            self.submit_pairing_code(code)
            self._log(f"Bluetooth confirm code sent: {code}")
        elif code:
            self._log(f"Invalid Bluetooth code: must be exactly 6 digits, got '{code}'")
        else:
            self._log("No Bluetooth confirm code entered")


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("SNDCPY")
    app.setOrganizationName("SNDCPY")
    
    window = SNDCPYWindow()
    window.show()
    
    sys.exit(app.exec())


if __name__ == "__main__":
    main()

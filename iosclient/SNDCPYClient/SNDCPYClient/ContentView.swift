import SwiftUI
import Network
import CryptoKit
import AVFoundation
import CoreMedia
import Darwin

// MARK: - Envelope
enum Envelope {
    static let CH_CONTROL: UInt8 = 0x01
    static let CH_VIDEO: UInt8 = 0x02
    static let CH_AUDIO: UInt8 = 0x03
    static let CH_TELEMETRY: UInt8 = 0x04

    static func decode(_ data: Data) -> (channel: UInt8, ptsUs: Int64, payload: Data)? {
        guard data.count >= 9 else { return nil }
        let channel = data[data.startIndex]
        let ptsData = data.dropFirst().prefix(8)
        let pts = ptsData.withUnsafeBytes { $0.load(as: Int64.self) }.bigEndian
        let payload = data.dropFirst(9)
        return (channel, pts, Data(payload))
    }

    static func encode(channel: UInt8, ptsUs: Int64, payload: Data) -> Data {
        var d = Data([channel])
        var pts = ptsUs.bigEndian
        d.append(Data(bytes: &pts, count: 8))
        d.append(payload)
        return d
    }
}

// MARK: - Shared video sink
@MainActor
final class VideoSink: ObservableObject {
    var display: AVSampleBufferDisplayLayer?
    private var formatDescription: CMVideoFormatDescription?

    func feed(_ payload: Data, _ ptsUs: Int64) {
        guard let display = display else { return }
        // On iOS, AVSampleBufferDisplayLayer needs a format description built
        // from the SPS/PPS. We build it once from the first access unit.
        if formatDescription == nil {
            buildFormatDescription(from: payload)
        }
        guard let fmt = formatDescription else { return }

        // Wrap the payload as an Annex-B sample buffer.
        var blockBuffer: CMBlockBuffer?
        let status = payload.withUnsafeBytes { ptr in
            CMBlockBufferCreateWithMemoryBlock(
                allocator: kCFAllocatorDefault,
                memoryBlock: UnsafeMutableRawPointer(mutating: ptr.baseAddress!),
                blockLength: payload.count,
                blockAllocator: kCFAllocatorNull,
                customBlockSource: nil,
                offsetToData: 0,
                dataLength: payload.count,
                flags: 0,
                blockBufferOut: &blockBuffer)
        }
        guard status == kCMBlockBufferNoErr, let bb = blockBuffer else { return }

        var timing = CMSampleTimingInfo()
        timing.presentationTimeStamp = CMTime(value: ptsUs, timescale: 1_000_000)
        timing.duration = CMTime(value: 33333, timescale: 1_000_000)
        timing.decodeTimeStamp = .invalid

        var sampleBuffer: CMSampleBuffer?
        CMSampleBufferCreateReady(allocator: kCFAllocatorDefault,
                                  dataBuffer: bb,
                                  formatDescription: fmt,
                                  sampleCount: 1,
                                  sampleTimingEntryCount: 1,
                                  sampleTimingArray: &timing,
                                  sampleSizeEntryCount: 0,
                                  sampleSizeArray: nil,
                                  sampleBufferOut: &sampleBuffer)
        if let sb = sampleBuffer {
            if display.isReadyForMoreMediaData {
                display.enqueue(sb)
            }
            if display.status == .failed {
                display.flush()
            }
        }
    }

    private func buildFormatDescription(from payload: Data) {
        // Find SPS (type 7) and PPS (type 8) NALs.
        let (sps, pps) = extractParameterSets(payload)
        guard let sps = sps, let pps = pps else { return }
        let paramSetPtrs = [UnsafePointer<UInt8>(OpaquePointer(sps.withUnsafeBytes { $0.baseAddress! })),
                            UnsafePointer<UInt8>(OpaquePointer(pps.withUnsafeBytes { $0.baseAddress! }))]
        let sizes = [sps.count, pps.count]
        var fmt: CMVideoFormatDescription?
        let status = CMVideoFormatDescriptionCreateFromH264ParameterSets(
            allocator: kCFAllocatorDefault,
            parameterSetCount: 2,
            parameterSetPointers: paramSetPtrs,
            parameterSetSizes: sizes,
            nalUnitHeaderLength: 4,
            formatDescriptionOut: &fmt)
        if status == noErr {
            formatDescription = fmt
        }
    }

    private func extractParameterSets(_ data: Data) -> (Data?, Data?) {
        let startCode = Data([0x00, 0x00, 0x00, 0x01])
        // Find each NAL by scanning start codes.
        var nals: [(Int, Data)] = []
        var idx = 0
        while idx <= data.count - 4 {
            let sc = data.subdata(in: data.index(data.startIndex, offsetBy: idx)..<data.index(data.startIndex, offsetBy: idx + 4))
            if sc == startCode {
                let nalStart = idx + 4
                var end = data.count
                var j = nalStart
                while j <= data.count - 4 {
                    let sc2 = data.subdata(in: data.index(data.startIndex, offsetBy: j)..<data.index(data.startIndex, offsetBy: j + 4))
                    if sc2 == startCode { end = j; break }
                    j += 1
                }
                if end > nalStart {
                    let nal = Data(data[data.index(data.startIndex, offsetBy: nalStart)..<data.index(data.startIndex, offsetBy: end)])
                    nals.append((nalStart - 4, nal))
                    idx = end
                    continue
                }
            }
            idx += 1
        }
        var sps: Data?
        var pps: Data?
        for (_, nal) in nals {
            guard let first = nal.first else { continue }
            let type = Int(first) & 0x1F
            if type == 7 && sps == nil { sps = nal }
            if type == 8 && pps == nil { pps = nal }
        }
        return (sps, pps)
    }
}

// MARK: - H.264 display view
struct H264View: UIViewRepresentable {
    @ObservedObject var sink: VideoSink

    func makeUIView(context: Context) -> UIView {
        let v = UIView()
        v.backgroundColor = .black
        let layer = AVSampleBufferDisplayLayer()
        layer.videoGravity = .resizeAspect
        layer.frame = v.bounds
        layer.needsDisplayOnBoundsChange = true
        v.layer.addSublayer(layer)
        sink.display = layer
        return v
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
}

// MARK: - Multi-touch input surface
/// Captures true multi-touch using standard UIKit touches and forwards
/// pointer commands (POINTER_DOWN/MOVE/UP per touch id) to the engine.
final class TouchSurfaceView: UIView {
    var onCommand: ((String) -> Void)?
    private let serverW: CGFloat = 1280
    private let serverH: CGFloat = 720

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        isMultipleTouchEnabled = true
    }

    private func command(_ prefix: String, _ touch: UITouch) {
        let p = touch.location(in: self)
        let sx = p.x / max(bounds.width, 1) * serverW
        let sy = p.y / max(bounds.height, 1) * serverH
        onCommand?("\(prefix) \(touch.hash) \(sx) \(sy)")
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches { command("POINTER_DOWN", t) }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches { command("POINTER_MOVE", t) }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches { onCommand?("POINTER_UP \(t.hash)") }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches { onCommand?("POINTER_UP \(t.hash)") }
    }
}

struct TouchSurface: UIViewRepresentable {
    @ObservedObject var engine: SNDCPYEngine

    func makeUIView(context: Context) -> TouchSurfaceView {
        let v = TouchSurfaceView()
        v.onCommand = { text in
            Task { @MainActor in
                engine.sendControl(text)
            }
        }
        return v
    }

    func updateUIView(_ uiView: TouchSurfaceView, context: Context) {}
}

// MARK: - UDP discovery observer
/// Listens for SNDCPY UDP beacons on the LAN. The server broadcasts
/// `SNDCPYv1 ip=<ip> port=<port> name=<name> proto=2 secure=1` every 3s
/// to 255.255.255.255:27184; we bind the same port and parse the packets.
final class DiscoveryObserver {
    struct Discovered {
        let ip: String
        let port: UInt16
        let name: String
    }

    var onFound: ((Discovered) -> Void)?

    private var socket: Int32 = -1
    private var running = false
    private let port: UInt16 = 27184
    private let queue = DispatchQueue(label: "sndcpy.discovery")

    func start() {
        guard !running else { return }
        running = true
        queue.async { [weak self] in
            self?.run()
        }
    }

    func stop() {
        running = false
        if socket >= 0 {
            close(socket)
            socket = -1
        }
    }

    private func run() {
        var sock = Darwin.socket(AF_INET, SOCK_DGRAM, 0)
        if sock < 0 { return }
        var yes: Int32 = 1
        setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = port.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY

        let bindResult = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { saPtr in
                bind(sock, saPtr, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if bindResult != 0 {
            close(sock)
            return
        }

        socket = sock
        while running {
            var buffer = [UInt8](repeating: 0, count: 512)
            var fromAddr = sockaddr_in()
            var fromLen = socklen_t(MemoryLayout<sockaddr_in>.size)
            let received = withUnsafeMutablePointer(to: &fromAddr) { ptr in
                ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { saPtr in
                    recvfrom(sock, &buffer, buffer.count, 0, saPtr, &fromLen)
                }
            }
            guard received > 0 else {
                if !running { break }
                usleep(100_000) // 100ms
                continue
            }
            let text = String(bytes: buffer[0..<received], encoding: .utf8) ?? ""
            guard text.hasPrefix("SNDCPYv1") else { continue }
            var ip: String?
            var portVal: UInt16?
            var name: String?
            for token in text.split(separator: " ") {
                let parts = token.split(separator: "=", maxSplits: 1)
                guard parts.count == 2 else { continue }
                switch parts[0] {
                case "ip": ip = String(parts[1])
                case "port": portVal = UInt16(parts[1])
                case "name": name = String(parts[1])
                default: break
                }
            }
            if let ip = ip, let portVal = portVal {
                let found = Discovered(ip: ip, port: portVal, name: name ?? "SNDCPY")
                DispatchQueue.main.async { self.onFound?(found) }
            }
        }
        if socket >= 0 {
            close(socket)
            socket = -1
        }
    }
}

// MARK: - Engine
@MainActor
final class SNDCPYEngine: ObservableObject {
    enum State: Equatable {
        case idle, connecting, pairing, connected, failed(String)
        var isConnected: Bool {
            if case .connected = self { return true }
            return false
        }
    }
    @Published var state: State = .idle
    let sink = VideoSink()

    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "sndcpy.client.queue")
    private let keyPair = P256.KeyAgreement.PrivateKey()
    private let clientNonce = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
    private var serverNonce = Data()
    private var sessionKey: SymmetricKey?
    private var readFrames = true

    var onPairingRequired: (() -> Void)?
    private var pairingCode: String?

    func setPairingCode(_ code: String) { pairingCode = code }

    func connect(host: String, port: UInt16) {
        let conn = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        self.connection = conn

        conn.stateUpdateHandler = { [weak self] state in
            guard let engine = self else { return }
            switch state {
            case .ready:
                Task { @MainActor in
                    engine.state = .pairing
                }
                let pub = engine.keyPair.publicKey.rawRepresentation.base64EncodedString()
                let nonce = engine.clientNonce.base64EncodedString()
                let hello = "HELLO SNDCPY \(pub) \(nonce)\n"
                conn.send(content: hello.data(using: .utf8), completion: .contentProcessed { _ in
                    engine.readTextLine { line in engine.handleHandshake(line) }
                })
            case .failed(let e):
                Task { @MainActor in
                    engine.state = .failed(e.localizedDescription)
                }
            case .cancelled:
                Task { @MainActor in
                    engine.state = .idle
                }
            default: break
            }
        }
        conn.start(queue: queue)
        state = .connecting
    }

    private func readTextLine(_ done: @escaping (String) -> Void) {
        guard let conn = connection else { return }
        var collected = Data()
        func read() {
            conn.receive(minimumIncompleteLength: 1, maximumLength: 4096) { data, _, _, _ in
                guard let data = data else { return }
                collected.append(data)
                if let nl = collected.firstIndex(of: 0x0A) {
                    let line = String(data: collected.prefix(upTo: nl), encoding: .utf8) ?? ""
                    done(line)
                } else {
                    read()
                }
            }
        }
        read()
    }

    private func handleHandshake(_ line: String) {
        let parts = line.split(separator: " ").map(String.init)
        guard parts.count >= 3 else { state = .failed("Bad handshake"); return }
        switch parts[0] {
        case "SECURE":
            serverNonce = Data(base64Encoded: parts[2]) ?? Data()
            deriveSession(serverPublicB64: parts[1])
            finishConnect()
        case "PAIRING_REQUIRED":
            serverNonce = Data(base64Encoded: parts[2]) ?? Data()
            deriveSession(serverPublicB64: parts[1])
            onPairingRequired?()
            waitForCode { [weak self] code in
                guard let self = self else { return }
                self.connection?.send(content: "PAIR \(code)\n".data(using: .utf8), completion: .contentProcessed { _ in
                    self.readTextLine { resp in
                        if resp.trimmingCharacters(in: .whitespaces) == "PAIRED" {
                            self.finishConnect()
                        } else {
                            self.state = .failed("Pairing rejected")
                        }
                    }
                })
            }
        default:
            state = .failed(line)
        }
    }

    private func waitForCode(_ done: @escaping (String) -> Void) {
        Task { @MainActor in
            var attempts = 0
            while pairingCode == nil && attempts < 300 {
                try? await Task.sleep(nanoseconds: 100_000_000)
                attempts += 1
            }
            done(pairingCode ?? "")
        }
    }

    private func deriveSession(serverPublicB64: String) {
        guard let raw = Data(base64Encoded: serverPublicB64),
              let spub = try? P256.KeyAgreement.PublicKey(x963Representation: raw) else {
            state = .failed("Bad server key"); return
        }
        let salt = clientNonce + serverNonce
        do {
            let shared = try keyPair.sharedSecretFromKeyAgreement(with: spub)
            let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self,
                                                     salt: salt,
                                                     sharedInfo: Data("SNDCPY-SESSION-KEY-v1".utf8),
                                                     outputByteCount: 32)
            sessionKey = key
        } catch {
            state = .failed("Session key failed")
        }
    }

    private func finishConnect() {
        state = .connected
        readFrames = true
        readFrameLoop()
    }

    private func readFrameLoop() {
        guard readFrames else { return }
        readFrame { [weak self] in
            guard let self = self else { return }
            self.readFrameLoop()
        }
    }

    private func readFrame(_ next: @escaping () -> Void) {
        guard let conn = connection else { return }
        var length: Int32?
        var body = Data()

        func step() {
            let want = max(4, Int(length ?? 4))
            conn.receive(minimumIncompleteLength: (length == nil ? 4 : 1),
                         maximumLength: min(max(65536, want), 1 << 20)) { data, _, isComplete, error in
                guard let data = data, error == nil else { self.readFrames = false; return }
                if length == nil {
                    guard data.count >= 4 else { self.readFrames = false; return }
                    let raw = data.prefix(4).withUnsafeBytes { $0.load(as: UInt32.self) }
                    let be = UInt32(bigEndian: raw)
                    length = Int32(be)
                    body = Data(data.dropFirst(4))
                } else {
                    body.append(contentsOf: data)
                }
                if body.count >= Int(length!) {
                    self.processFrame(Data(body.prefix(Int(length!))))
                    if isComplete { self.readFrames = false }
                    next()
                } else {
                    step()
                }
            }
        }
        step()
    }

    private func processFrame(_ frame: Data) {
        guard let key = sessionKey, frame.count > 12 else { return }
        guard let box = try? AES.GCM.SealedBox(combined: frame),
              let plain = try? AES.GCM.open(box, using: key) else { return }
        guard let env = Envelope.decode(plain) else { return }
        if env.channel == Envelope.CH_VIDEO {
            sink.feed(env.payload, env.ptsUs)
        }
    }

    func sendControl(_ text: String) {
        guard let key = sessionKey else { return }
        let env = Envelope.encode(channel: Envelope.CH_CONTROL, ptsUs: Int64(Date().timeIntervalSince1970 * 1_000_000), payload: Data(text.utf8))
        guard let encrypted = try? AES.GCM.seal(env, using: key).combined else { return }
        var len = UInt32(encrypted.count).bigEndian
        var frame = Data()
        frame.append(Data(bytes: &len, count: 4))
        frame.append(encrypted)
        connection?.send(content: frame, completion: .contentProcessed { _ in })
    }

    func disconnect() {
        readFrames = false
        connection?.cancel()
        connection = nil
        sessionKey = nil
        state = .idle
    }
}

// MARK: - Content View
struct ContentView: View {
    @StateObject private var engine = SNDCPYEngine()
    @State private var host = "192.168.1.42"
    @State private var port: UInt16 = 27183
    @State private var keyboardText = ""
    @State private var lastKeyboard = ""
    @State private var showPairing = false
    @State private var pairingInput = ""
    @State private var discoveryStatus = "Searching…"
    @State private var discoveredServers: [DiscoveryObserver.Discovered] = []

    private let discovery = DiscoveryObserver()

    var body: some View {
        NavigationView {
            VStack(spacing: 12) {
                // Discovered servers list
                if !discoveredServers.isEmpty {
                    ScrollView {
                        LazyVStack {
                            ForEach(Array(discoveredServers.enumerated()), id: \.element.ip) { _, srv in
                                Button("\(srv.name) @ \(srv.ip):\(srv.port)") {
                                    host = srv.ip
                                    port = srv.port
                                    engine.connect(host: srv.ip, port: srv.port)
                                }
                                .buttonStyle(.bordered)
                            }
                        }
                        .padding(.horizontal)
                        .frame(maxWidth: .infinity, alignment: .center)
                    }
                    .frame(height: 80)
                }

                HStack {
                    TextField("IP", text: $host)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.decimalPad)
                    TextField("Port", value: $port, format: .number)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.numberPad)
                        .frame(width: 80)
                    Button(engine.state.isConnected ? "Disconnect" : "Connect") {
                        if engine.state.isConnected { engine.disconnect() }
                        else { engine.connect(host: host, port: port) }
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(.horizontal)

                Text(discoveryStatus)
                    .font(.caption)
                    .foregroundColor(.gray)

                ZStack {
                    H264View(sink: engine.sink)
                    TouchSurface(engine: engine)
                    if !engine.state.isConnected {
                        ProgressView(engine.state == .connecting ? "Connecting…" : "Enter IP and Connect")
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                HStack {
                    Button("Home") { engine.sendControl("HOME") }
                    Button("Back") { engine.sendControl("BACK") }
                    Button("Recents") { engine.sendControl("RECENTS") }
                }
                .disabled(!engine.state.isConnected)

                TextField("This keyboard drives the Android device", text: $keyboardText)
                    .textFieldStyle(.roundedBorder)
                    .disabled(!engine.state.isConnected)
                    .padding(.horizontal)
                    .onChange(of: keyboardText) { newVal in
                        guard newVal.count >= lastKeyboard.count else {
                            lastKeyboard = newVal
                            return
                        }
                        if newVal.count > lastKeyboard.count {
                            let inserted = String(newVal.dropFirst(lastKeyboard.count))
                            engine.sendControl("TEXT:" + Data(inserted.utf8).base64EncodedString())
                        }
                        lastKeyboard = newVal
                    }
            }
            .navigationTitle("SNDCPY")
            .onAppear {
                engine.onPairingRequired = { DispatchQueue.main.async { showPairing = true } }
                discovery.onFound = { srv in
                    if !discoveredServers.contains(where: { $0.ip == srv.ip }) {
                        discoveredServers.append(srv)
                        discoveryStatus = "Found: \(srv.name)"
                    }
                }
                discovery.start()
            }
            .onDisappear {
                discovery.stop()
            }
            .overlay(
                ZStack {
                    if showPairing {
                        Color.black.opacity(0.6)
                            .ignoresSafeArea()
                        VStack(spacing: 20) {
                            Text("Pair with server")
                                .font(.title)
                                .foregroundColor(.white)
                            Text("Enter the 6-digit code shown on the server")
                                .foregroundColor(.gray)
                            TextField("6-digit code", text: $pairingInput)
                                .keyboardType(.numberPad)
                                .textFieldStyle(.roundedBorder)
                                .multilineTextAlignment(.center)
                                .frame(width: 180)
                            HStack(spacing: 12) {
                                Button("Cancel") {
                                    engine.setPairingCode("")
                                    showPairing = false
                                }
                                .buttonStyle(.bordered)
                                Button("Pair") {
                                    engine.setPairingCode(pairingInput)
                                    showPairing = false
                                }
                                .buttonStyle(.borderedProminent)
                            }
                        }
                        .padding(32)
                        .background(Color(.systemBackground))
                        .cornerRadius(16)
                    }
                }
            )
        }
    }
}

import Foundation
import Network

/// SNDCPY iOS Client — connects to the SNDCPY Android server over TCP.
/// Protocol: HELLO SNDCPY → WELCOME / PAIRING_REQUIRED → encrypted frames
final class SNDCPYClient {

    enum State {
        case disconnected
        case connecting
        case connected
        case failed(Error)
    }

    private var connection: NWConnection?
    private var state: State = .disconnected
    private let host: NWEndpoint.Host
    private let port: NWEndpoint.Port
    private let queue = DispatchQueue(label: "com.sndcpy.client.queue")

    var onMessage: ((String) -> Void)?
    var onStateChange: ((State) -> Void)?

    init(host: String, port: UInt16 = 27183) {
        self.host = NWEndpoint.Host(host)
        self.port = NWEndpoint.Port(rawValue: port)!
    }

    func connect() {
        state = .connecting
        onStateChange?(.connecting)

        let connection = NWConnection(host: host, port: port, using: .tcp)
        self.connection = connection

        connection.stateUpdateHandler = { [weak self] newState in
            guard let self = self else { return }
            switch newState {
            case .ready:
                self.state = .connected
                self.onStateChange?(.connected)
                self.sendHandshake()
                self.receive()
            case .failed(let error):
                self.state = .failed(error)
                self.onStateChange?(.failed(error))
            case .cancelled:
                self.state = .disconnected
                self.onStateChange?(.disconnected)
            default:
                break
            }
        }

        connection.start(queue: queue)
    }

    func disconnect() {
        connection?.cancel()
        connection = nil
        state = .disconnected
        onStateChange?(.disconnected)
    }

    func send(_ text: String) {
        guard let data = (text + "\n").data(using: .utf8) else { return }
        connection?.send(content: data, completion: .contentProcessed { _ in })
    }

    func sendCommand(_ command: String) {
        send(command)
    }

    // MARK: - Private

    private func sendHandshake() {
        send("HELLO SNDCPY")
    }

    private func receive() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if let data = data, !data.isEmpty, let text = String(data: data, encoding: .utf8) {
                let lines = text.components(separatedBy: .newlines).filter { !$0.isEmpty }
                for line in lines {
                    DispatchQueue.main.async {
                        self.onMessage?(line)
                    }
                }
            }
            if let error = error {
                self.state = .failed(error)
                self.onStateChange?(.failed(error))
                return
            }
            if !isComplete {
                self.receive()
            }
        }
    }
}

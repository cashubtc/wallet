import Foundation

/// Payer transport for the Apple-approved application. The NDEF file and APDUs
/// are unchanged from Numo; only application selection differs. Never retry a
/// token write automatically after an ambiguous final acknowledgment.
@MainActor
final class NFCType4Reader {
    private let exchange: (Data) async throws -> Data
    private var readSize = 0
    private var writeSize = 0
    private var capacity = 0
    private(set) var isWritable = false

    init(exchange: @escaping (Data) async throws -> Data) {
        self.exchange = exchange
    }

    func read() async throws -> Data {
        capacity = 0
        isWritable = false
        _ = try await command([0, 0xA4, 4, 0, UInt8(NFCType4Tag.aid.count)] + NFCType4Tag.aid + [0])
        _ = try await command([0, 0xA4, 0, 0x0C, 2, 0xE1, 3])
        let cc = [UInt8](try await readBytes(offset: 0, count: 15))
        guard number(cc, 0) >= 15, cc[2] >> 4 == 2, cc[7] == 4, cc[8] == 6,
              cc[13] == 0 else { throw failure("Unsupported payment tag.") }
        readSize = min(number(cc, 3), 255)
        writeSize = min(number(cc, 5), 255)
        capacity = min(number(cc, 11), NFCType4Tag.maxMessageSize)
        isWritable = cc[14] == 0 && writeSize >= 2
        guard readSize >= 2, capacity >= 2 else { throw failure("Invalid payment tag limits.") }
        _ = try await command([0, 0xA4, 0, 0x0C, 2, cc[9], cc[10]])
        let header = [UInt8](try await readBytes(offset: 0, count: 2))
        let length = number(header, 0)
        guard length > 0, length <= capacity - 2 else { throw failure("Invalid payment request length.") }
        var body = Data()
        while body.count < length {
            body += try await readBytes(offset: body.count + 2, count: min(readSize, length - body.count))
        }
        return body
    }

    func write(text: String) async throws {
        let file = try NFCNdefCodec.textFile(text)
        guard isWritable, file.count <= capacity else { throw failure("Payment tag is read-only or the token is too large.") }
        // Commit NLEN last: the receiver must not accept a partially written token.
        try await update(offset: 0, bytes: [0, 0])
        for offset in stride(from: 2, to: file.count, by: writeSize) {
            try await update(offset: offset, bytes: Array(file[offset..<min(offset + writeSize, file.count)]))
        }
        try await update(offset: 0, bytes: Array(file.prefix(2)))
    }

    private func update(offset: Int, bytes: [UInt8]) async throws {
        _ = try await command([0, 0xD6, UInt8(offset >> 8), UInt8(offset & 255), UInt8(bytes.count)] + bytes)
    }

    private func readBytes(offset: Int, count: Int) async throws -> Data {
        let result = try await command([0, 0xB0, UInt8(offset >> 8), UInt8(offset & 255), UInt8(count)])
        guard result.count == count else { throw failure("Incomplete payment tag response.") }
        return result
    }

    private func command(_ bytes: [UInt8]) async throws -> Data {
        try Task.checkCancellation()
        let response = try await exchange(Data(bytes))
        guard response.count >= 2, response.suffix(2) == Data([0x90, 0]) else {
            throw failure("The payment tag rejected the transfer. Keep the devices together and try again.")
        }
        return response.dropLast(2)
    }

    private func number(_ bytes: [UInt8], _ offset: Int) -> Int {
        Int(bytes[offset]) << 8 | Int(bytes[offset + 1])
    }

    private func failure(_ message: String) -> NFCReceiveError { .message(message) }
}

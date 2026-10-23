import CoreNFC
import XCTest
@testable import CashuWallet

final class NDEFTextRecordTests: XCTestCase {
    func testRejectsMessagesWithInvalidRecordLengths() {
        for length: UInt32 in [0x7fff_ffff, 0x7fff_fff9, 0x8000_0000, 0xffff_ffff, 0x7100] {
            let bytes: [UInt8] = [0xc1, 1,
                                  UInt8(truncatingIfNeeded: length >> 24),
                                  UInt8(truncatingIfNeeded: length >> 16),
                                  UInt8(truncatingIfNeeded: length >> 8),
                                  UInt8(truncatingIfNeeded: length), 0x54]
            let message = NFCNDEFMessage(data: Data(bytes))
            XCTAssertNil(message.flatMap { NDEFTextRecord.extractText(from: $0) })
        }
    }

    func testDecodesRawShortAndNonShortRecords() throws {
        let payload: [UInt8] = [2, 0x65, 0x6e] + Array("token".utf8)
        for header: [UInt8] in [[0xd1, 1, UInt8(payload.count)], [0xc1, 1, 0, 0, 0, UInt8(payload.count)]] {
            let message = try XCTUnwrap(NFCNDEFMessage(data: Data(header + [0x54] + payload)))
            XCTAssertEqual(NDEFTextRecord.extractText(from: message), "token")
        }
    }

    func testShortAndLongTextMessages() {
        for length in [1, 252, 253, 400] {
            let text = String(repeating: "x", count: length)
            XCTAssertEqual(NDEFTextRecord.extractText(from: NDEFTextRecord.makeMessage(with: text)), text)
        }
    }

    func testMessageSizeBoundary() {
        let text = String(repeating: "x", count: NDEFTextRecord.maxMessageSize - 10)
        let message = NDEFTextRecord.makeMessage(with: text)
        XCTAssertEqual(message.length, NDEFTextRecord.maxMessageSize)
        XCTAssertEqual(NDEFTextRecord.extractText(from: message), text)

        let oversized = NDEFTextRecord.makeMessage(with: text + "x")
        XCTAssertEqual(oversized.length, NDEFTextRecord.maxMessageSize + 1)
        XCTAssertNil(NDEFTextRecord.extractText(from: oversized))
    }

    func testOversizedMessageCannotReturnAnEarlierRecord() {
        let first = NDEFTextRecord.makeMessage(with: "token")
        let second = NDEFTextRecord.makeMessage(with: String(repeating: "x", count: NDEFTextRecord.maxMessageSize))
        XCTAssertNil(NDEFTextRecord.extractText(from: NFCNDEFMessage(records: first.records + second.records)))
    }

    func testRejectsIncompleteTextPayloads() {
        for payload in [Data(), Data([2]), Data([2, 0x65]), Data([2, 0x65, 0x6e])] {
            let record = NFCNDEFPayload(format: .nfcWellKnown, type: Data("T".utf8), identifier: Data(), payload: payload)
            XCTAssertNil(NDEFTextRecord.extractText(from: NFCNDEFMessage(records: [record])))
        }
    }

    func testURIMessage() throws {
        let record = try XCTUnwrap(NFCNDEFPayload.wellKnownTypeURIPayload(string: "https://example.com/pay"))
        XCTAssertEqual(NDEFTextRecord.extractText(from: NFCNDEFMessage(records: [record])), "https://example.com/pay")
    }

    func testMediaAndExternalMessages() {
        for format in [NFCTypeNameFormat.media, .nfcExternal] {
            let record = NFCNDEFPayload(format: format, type: Data("text/plain".utf8), identifier: Data(), payload: Data("token".utf8))
            XCTAssertEqual(NDEFTextRecord.extractText(from: NFCNDEFMessage(records: [record])), "token")
        }
    }
}

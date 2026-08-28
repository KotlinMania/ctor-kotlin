#if canImport(Testing)
import Testing
import Ctor

@Suite("Ctor Swift Export Tests")
struct CtorExportTests {
    @Test("Swift module loads")
    func testSwiftModuleLoads() {
        #expect(Bool(true), "Ctor swift module imported cleanly")
    }
}
#elseif canImport(XCTest)
import XCTest
import Ctor

final class CtorExportTests: XCTestCase {
    func testSwiftModuleLoads() throws {
        XCTAssertTrue(true, "Ctor swift module imported cleanly")
    }
}
#endif

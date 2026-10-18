import UIKit
import XCTest
@testable import TransparentArt

final class SketchEngineTests: XCTestCase {
    /// Red square on a green background of nearly the same luminance.
    private func colourSquare(width w: Int = 400, height h: Int = 300) -> [UInt32] {
        (0..<(w * h)).map { i in
            let x = i % w
            let y = i / w
            return (100..<300).contains(x) && (75..<225).contains(y) ? 0xFFD7_4646 : 0xFF3C_A03C
        }
    }

    func testColourBordersOfEqualBrightnessBecomeLines() {
        let w = 400
        let h = 300
        var params = SketchParams()
        params.shadeLevels = 0
        let ink = SketchEngine(argb: colourSquare(width: w, height: h), width: w, height: h).render(params)
        func alpha(_ x: Int, _ y: Int) -> UInt32 { ink[y * w + x] >> 24 }

        XCTAssertTrue((96...104).contains { alpha($0, 150) > 128 }, "left border is drawn")
        XCTAssertTrue((71...79).contains { alpha(200, $0) > 128 }, "top border is drawn")
        XCTAssertEqual(alpha(200, 150), 0, "flat inside stays empty")
        XCTAssertEqual(alpha(30, 30), 0, "flat outside stays empty")
    }

    func testFinishUsesInkColourAndKeepsBackgroundTransparent() {
        let w = 40
        let h = 30
        let engine = SketchEngine(argb: [UInt32](repeating: 0xFF80_8080, count: w * h), width: w, height: h)
        var params = SketchParams()
        params.shadeLevels = 0
        params.inkColor = 0xE53935

        var lines = [Float](repeating: 0, count: w * h)
        lines[5] = 1
        lines[6] = 0.5
        let out = engine.finish(lines: lines, params: params)

        XCTAssertEqual(out[0] >> 24, 0, "empty pixels are fully transparent")
        XCTAssertEqual(out[5], 0xFF00_0000 | 0xE53935)
        XCTAssertEqual(out[6] >> 24, 128)
        XCTAssertEqual(out[6] & 0x00FF_FFFF, 0xE53935)
    }

    func testImageLoaderEnlargesSmallPicturesAndKeepsAspectRatio() throws {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 100, height: 50), format: {
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            return format
        }())
        let png = renderer.pngData { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 100, height: 50))
        }

        let loaded = try XCTUnwrap(ImageLoader.load(data: png))
        XCTAssertEqual(loaded.width, 400)
        XCTAssertEqual(loaded.height, 200)
        XCTAssertEqual(loaded.pixels.count, 400 * 200)
        let pixel = loaded.pixels[100 * 400 + 200]
        XCTAssertEqual(pixel >> 24, 255)
        XCTAssertEqual((pixel >> 16) & 0xFF, 255, "red channel")
        XCTAssertLessThan(pixel & 0xFF, 10, "blue channel")
    }

    func testUpscaleKeepsConstantImagesConstant() {
        let src = [Float](repeating: 0.25, count: 6 * 4)
        let out = NeuralSketcher.upscale(src, sourceWidth: 6, sourceHeight: 4, width: 23, height: 17)
        XCTAssertEqual(out.count, 23 * 17)
        XCTAssertTrue(out.allSatisfy { abs($0 - 0.25) < 1e-6 })
    }
}

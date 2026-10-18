import UIKit
import XCTest
@testable import TransparentArt

/// Runs the real ONNX models that ship in the app bundle.
final class NeuralSketcherTests: XCTestCase {
    private let width = 400
    private let height = 300

    /// A red square and a dark disc on a green background.
    private func samplePicture() throws -> LoadedImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format)
        let png = renderer.pngData { context in
            UIColor(red: 0.24, green: 0.63, blue: 0.24, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
            UIColor(red: 0.84, green: 0.27, blue: 0.27, alpha: 1).setFill()
            context.fill(CGRect(x: 100, y: 75, width: 200, height: 150))
            UIColor(white: 0.1, alpha: 1).setFill()
            context.cgContext.fillEllipse(in: CGRect(x: 160, y: 110, width: 80, height: 80))
        }
        return try XCTUnwrap(ImageLoader.load(data: png))
    }

    func testBothModelsDrawLinesAroundShapes() throws {
        let picture = try samplePicture()
        let neural = NeuralSketcher()
        for style in NeuralStyle.allCases {
            let ink = try neural.draw(source: picture.cgImage, style: style)
            XCTAssertEqual(ink.count, picture.width * picture.height, "\(style) output size")
            XCTAssertTrue(ink.allSatisfy { $0 >= 0 && $0 <= 1 }, "\(style) values are ink amounts")

            func mean(_ xs: Range<Int>, _ ys: Range<Int>) -> Float {
                var sum: Float = 0
                var n: Float = 0
                for y in ys { for x in xs { sum += ink[y * picture.width + x]; n += 1 } }
                return sum / n
            }
            // The scene is scaled up 3.2x to 1280 px wide; the square's left edge is at x = 320.
            let w = picture.width
            let scaleUp = Float(w) / 400
            let edgeX = Int(100 * scaleUp)
            let edge = mean((edgeX - 8)..<(edgeX + 8), Int(120 * scaleUp)..<Int(180 * scaleUp))
            let flat = mean(10..<60, 10..<60)
            XCTAssertGreaterThan(edge, flat + 0.02, "\(style): the border is inked more than empty background")
        }
    }

    func testProcessorRendersBothModesAndShading() throws {
        let picture = try samplePicture()
        let processor = SketchProcessor(image: picture, neural: NeuralSketcher())
        var params = SketchParams()
        for mode in SketchMode.allCases {
            params.mode = mode
            for levels in [0, 3] {
                params.shadeLevels = levels
                let image = try processor.render(params)
                XCTAssertEqual(image.width, picture.width)
                XCTAssertEqual(image.height, picture.height)
            }
        }
    }
}

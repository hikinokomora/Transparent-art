import CoreGraphics
import Foundation
import ImageIO

/// A decoded picture: the image itself for display and one 0xAARRGGBB pixel per entry
/// for the filters. Transparent areas are already composited onto white paper.
struct LoadedImage: @unchecked Sendable {
    let cgImage: CGImage
    let pixels: [UInt32]
    let width: Int
    let height: Int
}

enum ImageLoader {
    /// Decodes a gallery image, rotated upright according to its EXIF data and scaled so its longest
    /// side is `maxSide` (small images are enlarged at most 4×: the filters are tuned for this size).
    static func load(data: Data, maxSide: Int = 1280) -> LoadedImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        // ImageIO may enlarge a picture that is smaller than the maximum size, so cap it at the real size.
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        let pixelWidth = properties?[kCGImagePropertyPixelWidth] as? Int ?? 0
        let pixelHeight = properties?[kCGImagePropertyPixelHeight] as? Int ?? 0
        let longest = max(pixelWidth, pixelHeight)
        guard longest > 0 else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: min(longest, maxSide),
        ]
        guard let decoded = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
              decoded.width > 0, decoded.height > 0 else { return nil }

        let factor = min(4, Float(maxSide) / Float(max(decoded.width, decoded.height)))
        let width = max(1, roundInt(Float(decoded.width) * factor))
        let height = max(1, roundInt(Float(decoded.height) * factor))

        // Premultiplied ARGB with little-endian byte order reads back as 0xAARRGGBB.
        var pixels = [UInt32](repeating: 0xFFFF_FFFF, count: width * height)
        let bitmapInfo = CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        var image: CGImage?
        pixels.withUnsafeMutableBytes { buffer in
            guard let space = CGColorSpace(name: CGColorSpace.sRGB),
                  let context = CGContext(
                      data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                      bytesPerRow: width * 4, space: space, bitmapInfo: bitmapInfo
                  ) else { return }
            let rect = CGRect(x: 0, y: 0, width: width, height: height)
            context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
            context.fill(rect)
            context.interpolationQuality = .high
            context.draw(decoded, in: rect)
            image = context.makeImage()
        }
        guard let cgImage = image else { return nil }
        return LoadedImage(cgImage: cgImage, pixels: pixels, width: width, height: height)
    }
}

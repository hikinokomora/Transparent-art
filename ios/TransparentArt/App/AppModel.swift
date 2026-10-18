import SwiftUI
import UIKit

/// Where the overlay sits on the camera screen.
struct TraceState: Equatable {
    var scale: CGFloat = 1
    var offset: CGSize = .zero
    var rotation: Angle = .zero
    var alpha: Double = 0.5
    var flipped = false
    var locked = false
}

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var original: UIImage?
    @Published private(set) var sketch: UIImage?
    @Published private(set) var params = SketchParams()
    @Published var trace = TraceState()
    @Published private(set) var loading = false
    @Published private(set) var processing = false
    @Published var loadFailed = false
    @Published var neuralFailed = false

    private let neural = NeuralSketcher()
    private var processor: SketchProcessor?
    private var renderTask: Task<Void, Never>?

    func loadImage(data: Data) async {
        loading = true
        let image = await Task.detached(priority: .userInitiated) { ImageLoader.load(data: data) }.value
        guard let image else {
            loadFailed = true
            loading = false
            return
        }
        renderTask?.cancel()
        let neural = self.neural
        processor = await Task.detached(priority: .userInitiated) {
            SketchProcessor(image: image, neural: neural)
        }.value
        original = UIImage(cgImage: image.cgImage)
        sketch = nil
        var fresh = TraceState()
        fresh.alpha = trace.alpha
        trace = fresh
        loading = false
        scheduleRender(delay: .zero)
    }

    func updateParams(_ newParams: SketchParams) {
        params = newParams
        scheduleRender(delay: .milliseconds(120))
    }

    private func scheduleRender(delay: Duration) {
        guard let processor else { return }
        renderTask?.cancel()
        renderTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            if Task.isCancelled { return }
            guard let self else { return }
            self.processing = true

            var requested = self.params
            var result = await AppModel.render(processor, requested)
            if case .failure = result, requested.mode == .neural {
                // The model could not run on this device: fall back to the classic filter.
                requested.mode = .classic
                self.neuralFailed = true
                self.params = requested
                result = await AppModel.render(processor, requested)
            }
            if Task.isCancelled { return }
            if case .success(let image) = result {
                self.sketch = UIImage(cgImage: image)
            }
            self.processing = false
        }
    }

    private nonisolated static func render(_ processor: SketchProcessor, _ params: SketchParams) async -> Result<CGImage, Error> {
        await Task.detached(priority: .userInitiated) {
            Result { try processor.render(params) }
        }.value
    }
}

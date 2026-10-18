import SwiftUI

@main
struct TransparentArtApp: App {
    @StateObject private var model = AppModel()
    @State private var tracing = false

    var body: some Scene {
        WindowGroup {
            if tracing, let sketch = model.sketch {
                TraceView(sketch: sketch, trace: $model.trace, onBack: { tracing = false })
            } else {
                EditorView(model: model, onStartTracing: { tracing = true })
            }
        }
    }
}

import PhotosUI
import SwiftUI

struct EditorView: View {
    @ObservedObject var model: AppModel
    let onStartTracing: () -> Void

    @State private var pickerItem: PhotosPickerItem?

    var body: some View {
        NavigationStack {
            Group {
                if let original = model.original {
                    GeometryReader { geometry in
                        VStack(spacing: 0) {
                            SketchPreview(original: original, sketch: model.sketch, busy: model.loading || model.processing)
                                .frame(height: geometry.size.height * 0.45)
                            ControlsView(
                                params: Binding(get: { model.params }, set: { model.updateParams($0) }),
                                startEnabled: model.sketch != nil,
                                onStart: onStartTracing
                            )
                        }
                    }
                } else {
                    EmptyState(loading: model.loading, pickerItem: $pickerItem)
                }
            }
            .navigationTitle("Transparent Art")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if model.original != nil {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        PhotosPicker(selection: $pickerItem, matching: .images) {
                            Image(systemName: "photo.badge.plus")
                                .accessibilityLabel(Text("pick_photo"))
                        }
                    }
                }
            }
        }
        .task(id: pickerItem) {
            guard let item = pickerItem else { return }
            if let data = try? await item.loadTransferable(type: Data.self) {
                await model.loadImage(data: data)
            } else {
                model.loadFailed = true
            }
            pickerItem = nil
        }
        .alert("load_failed", isPresented: $model.loadFailed) {
            Button("ok", role: .cancel) {}
        }
        .alert("neural_failed", isPresented: $model.neuralFailed) {
            Button("ok", role: .cancel) {}
        }
    }
}

private struct EmptyState: View {
    let loading: Bool
    @Binding var pickerItem: PhotosPickerItem?

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "pencil.and.outline")
                .font(.system(size: 64))
                .foregroundColor(.accentColor)
            Text("pick_photo_hint")
                .multilineTextAlignment(.center)
            if loading {
                ProgressView()
            } else {
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Label("pick_photo", systemImage: "photo.badge.plus")
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(32)
    }
}

private struct SketchPreview: View {
    let original: UIImage
    let sketch: UIImage?
    let busy: Bool

    @State private var showOriginal = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.white
            Image(uiImage: showOriginal || sketch == nil ? original : sketch!)
                .resizable()
                .interpolation(.high)
                .scaledToFit()
                .padding(8)
            if busy {
                ProgressView()
                    .padding(12)
            }
        }
        .contentShape(Rectangle())
        .onLongPressGesture(minimumDuration: 0, maximumDistance: .infinity, pressing: { pressing in
            showOriginal = pressing
        }, perform: {})
    }
}

private struct ControlsView: View {
    @Binding var params: SketchParams
    let startEnabled: Bool
    let onStart: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Picker("", selection: $params.mode) {
                    Text("mode_neural").tag(SketchMode.neural)
                    Text("mode_classic").tag(SketchMode.classic)
                }
                .pickerStyle(.segmented)

                Text(params.mode == .neural ? "mode_neural_hint" : "mode_classic_hint")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                Text("hold_to_compare")
                    .font(.footnote)
                    .foregroundColor(.secondary)

                if params.mode == .neural {
                    Picker("", selection: $params.style) {
                        Text("style_contour").tag(NeuralStyle.contour)
                        Text("style_sketch").tag(NeuralStyle.sketch)
                    }
                    .pickerStyle(.segmented)
                    LabeledSlider(title: "cleanliness", value: $params.cleanliness)
                    LabeledSlider(title: "darkness", value: $params.darkness)
                } else {
                    LabeledSlider(title: "detail", value: $params.detail)
                    LabeledSlider(title: "sensitivity", value: $params.sensitivity)
                    LabeledSlider(title: "thickness", value: $params.thickness)
                }

                let shadingOn = params.shadeLevels >= 2
                LabeledSlider(
                    title: "shading_levels",
                    value: Binding<Double>(
                        get: { shadingOn ? Double(params.shadeLevels - 1) : 0 },
                        set: { newValue in
                            let n = Int(newValue.rounded())
                            params.shadeLevels = n == 0 ? 0 : n + 1
                        }
                    ),
                    range: 0...4,
                    step: 1,
                    valueLabel: shadingOn ? Text("\(params.shadeLevels)") : Text("shading_off")
                )
                LabeledSlider(title: "shading_strength", value: $params.shadeStrength)
                    .disabled(!shadingOn)

                Text("ink_color")
                    .padding(.top, 4)
                HStack(spacing: 12) {
                    ForEach(inkColors, id: \.self) { color in
                        Button {
                            params.inkColor = color
                        } label: {
                            Circle()
                                .fill(Color(hex: color))
                                .frame(width: 36, height: 36)
                                .overlay(
                                    Circle().stroke(
                                        params.inkColor == color ? Color.accentColor : Color.secondary,
                                        lineWidth: params.inkColor == color ? 3 : 1
                                    )
                                )
                        }
                        .buttonStyle(.plain)
                    }
                }

                Button(action: onStart) {
                    Label("start_tracing", systemImage: "pencil.tip")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(!startEnabled)
                .padding(.vertical, 8)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
    }
}

/// A titled slider with its value on the right: percent unless `valueLabel` is given.
private struct LabeledSlider<V: BinaryFloatingPoint>: View where V.Stride: BinaryFloatingPoint {
    let title: LocalizedStringKey
    @Binding var value: V
    var range: ClosedRange<V> = 0...1
    var step: V.Stride? = nil
    var valueLabel: Text? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(title)
                Spacer()
                (valueLabel ?? Text("\(Int((Double(value) * 100).rounded()))%"))
                    .foregroundColor(.secondary)
            }
            .font(.subheadline)
            if let step {
                Slider(value: $value, in: range, step: step)
            } else {
                Slider(value: $value, in: range)
            }
        }
    }
}

extension Color {
    /// 0xRRGGBB.
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

# ONNX Runtime's native code looks up its Java classes, fields and methods by name,
# and the library ships no consumer rules. Renaming them crashes the app on first inference.
-keep class ai.onnxruntime.** { *; }

# Gson uses TypeToken<...>(){} anonymous subclasses to recover generic type info (e.g.
# Map<String, String>) at runtime via reflection on the class's generic superclass signature -
# without this, that resolves to a raw, untyped Map and deserialization breaks.
-keepattributes Signature,*Annotation*,EnclosingMethod,InnerClasses

# Custom Views (EqVisualizerView, FmVisualizerView, SpectrumAnalyzerView, BalancePointerView,
# etc.) are referenced by fully-qualified class name from XML layouts and never directly
# `new`'d in Java, so nothing in the app's own code looks like a reference to them - R8 needs
# an explicit keep or it can strip/rename them, breaking layout inflation at runtime.
-keep class com.radiorubka.wdsp.** extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

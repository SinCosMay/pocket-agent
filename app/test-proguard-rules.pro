# Compiler-only type in Error Prone annotation metadata; never used by device tests.
-dontwarn javax.lang.model.element.Modifier

# Release instrumentation discovers test classes and private fixture connectors reflectively.
-keep class dev.pocket.agent.*DeviceTest** { *; }

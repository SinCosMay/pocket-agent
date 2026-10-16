-keep class com.caoccao.javet.** { *; }
-keep class dev.pocket.agent.RuntimeHost$NativeBridge { *; }
-keep class dev.pocket.agent.RuntimeHost { *; }
-keep class dev.pocket.agent.AgentService { *; }
-keep class dev.pocket.agent.CredentialStore { *; }
-keep class dev.pocket.agent.StateStore { *; }
-keep class dev.pocket.agent.NativeTools { *; }
-keep class dev.pocket.agent.Json { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-keep class dev.pocket.agent.RuntimeHost$Bridge { *; }
-keep class dev.pocket.agent.MainActivity { *; }
-keep class dev.pocket.agent.AgentAccessibility { *; }
-keep class dev.pocket.agent.ShellDisplayService { *; }
-keep class dev.pocket.agent.IDisplayShell** { *; }

-keep class dev.pocket.agent.CodexAccountAuth { *; }

# AndroidX instrumentation uses Kotlin classes also packaged by OkHttp.
# The test APK expects all runtime Kotlin types to remain linkable in the target.
-keep class kotlin.** { *; }

# Device instrumentation links these directly; R8 must retain them in the minified target APK.
-keep class dev.pocket.agent.VoiceTranscript { *; }
-keep class dev.pocket.agent.VoiceTextChunker { *; }

-keep class dev.pocket.agent.MemoryStore { *; }

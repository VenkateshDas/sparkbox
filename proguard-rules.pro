# jsoup's optional regex engine is not shipped
-dontwarn com.google.re2j.**
# org.json comes from the platform
-dontwarn org.json.**

# LiteRT-LM 0.18.1 reads these getters by name in nativeSendMessage.
-keepclassmembers class com.google.ai.edge.litertlm.ThinkingConfig {
    boolean getEnableThinking();
    int getThinkingTokenBudget();
}

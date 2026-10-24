# Lagoon 通过 GitHub Release 侧载分发，崩溃日志和"复制诊断"由用户直接贴出。
# 只做裁剪与优化，不混淆，保留源文件名和行号：贴出来的堆栈不需要 mapping.txt 也能读。
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

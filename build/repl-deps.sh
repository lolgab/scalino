# Sourced by 02a, 03, 08, 13 and pgo/pgo-dotc.sh: the jars of pprint, which the REPL prints values with
# (patches/scala3-0052), as the stock Scala 3 REPL does. dotc's sources compile against the JVM jars
# (REPL_CP: their TASTy is all the compile needs), the link takes the Scala Native cross-builds
# (REPL_NATIVE_CP). Fetched by each step rather than once in 01, like the LSP's jsoniter in 08:
# a CI job restoring another one's state may not have that job's coursier cache (Windows).
# --intransitive: their scala3-library/scalalib/nativelib would duplicate our own.
REPL_JVM_JARS=() REPL_NATIVE_JARS=()
for lib in pprint:0.9.6 fansi:0.5.1 sourcecode:0.4.3-M5; do
  REPL_JVM_JARS+=("$(cs fetch --intransitive "com.lihaoyi:${lib%%:*}_3:${lib#*:}" --classpath | tr -d '\r')")
  REPL_NATIVE_JARS+=("$(cs fetch --intransitive "com.lihaoyi:${lib%%:*}_native0.5_3:${lib#*:}" --classpath | tr -d '\r')")
done
REPL_CP="$(IFS="$CP_SEP"; echo "${REPL_JVM_JARS[*]}")"
REPL_NATIVE_CP="$(IFS="$CP_SEP"; echo "${REPL_NATIVE_JARS[*]}")"

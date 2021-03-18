set -e
set -x

fd -I -e apk . TMessagesProj/build/outputs/apk/afat/release/ | xargs -I {} cp {} /tmp/
cp TMessagesProj/build/outputs/mapping/afatRelease/mapping.txt /tmp/
cp TMessagesProj/build/outputs/native-debug-symbols/afatRelease/native-debug-symbols.zip /tmp/

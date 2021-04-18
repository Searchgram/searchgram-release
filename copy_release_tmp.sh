set -e
set -x

mkdir -p /tmp/searchgram-release

fd -I -e apk . TMessagesProj/build/outputs/apk/afat/release/ | xargs -I {} cp {} /tmp/searchgram-release/
cp TMessagesProj/build/outputs/mapping/afatRelease/mapping.txt /tmp/searchgram-release/
cp TMessagesProj/build/outputs/native-debug-symbols/afatRelease/native-debug-symbols.zip /tmp/searchgram-release/

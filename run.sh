
set +x
set -e

export JAVA_CMD=/usr/lib/jvm/java-8-oracle/jre/bin/java

lein run test --nodes-file css-nodes --ssh-private-key id_rsa

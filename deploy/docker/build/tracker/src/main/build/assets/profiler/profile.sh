#!/bin/bash
set -eu

SOURCE_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
pushd "${SOURCE_PATH}" >/dev/null || exit

DURATION="${1:-10}"
EVENT="${2:-itimer}"

SUFFIX=""
EXTRA_OPTS=""
if [ "${EVENT}" != "itimer" ]; then
  SUFFIX="-${EVENT}"
fi
if [ "${EVENT}" = "alloc" ]; then
  # accumulate total allocated bytes per stack instead of per-sample counts
  EXTRA_OPTS="--total"
fi

./async-profiler-2.0-linux-x64/profiler.sh -e "${EVENT}" ${EXTRA_OPTS} -o jfr -f "report${SUFFIX}.jfr" -d "${DURATION}" 1
java -cp ./async-profiler-2.0-linux-x64/build/converter.jar jfr2flame "report${SUFFIX}.jfr" "report${SUFFIX}.html"

popd >/dev/null || exit

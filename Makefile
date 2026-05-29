# ARCP Kotlin SDK — convenience targets.
#
# The www site at /Users/nficano/code/arpc/www ingests
# <lang>-sdk/docs/**/*.md at build time, so the `docs-api` target
# regenerates Markdown API docs from KDoc using Dokka's GFM plugin and
# writes them under docs/api/. The directory is git-ignored; the www
# build is the only consumer.

.PHONY: docs-api docs-api-clean

# Regenerate Markdown API docs (Dokka GFM) into docs/api/.
docs-api:
	./gradlew :lib:dokkaGenerate

# Remove generated API docs.
docs-api-clean:
	rm -rf docs/api

.PHONY: package redeploy-local redeploy-server debug

package:
	@./scripts/package.sh

redeploy-local:
	@./scripts/redeploy-local.sh

redeploy-server:
	@./scripts/redeploy-server.sh

debug:
	@./scripts/debug-run.sh

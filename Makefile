.PHONY: auth-sudo package redeploy-local redeploy-server debug fresh fresh-all

auth-sudo:
	@sudo -v

package:
	@./scripts/package.sh

redeploy-local: auth-sudo
	@./scripts/redeploy-local.sh

redeploy-server:
	@./scripts/redeploy-server.sh

debug:
	@./scripts/debug-run.sh

fresh: auth-sudo package redeploy-local

fresh-all: auth-sudo package redeploy-server redeploy-local
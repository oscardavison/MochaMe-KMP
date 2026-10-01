.PHONY: auth-sudo package redeploy-mochame redeploy-server debug fresh fresh-all

auth-sudo:
	@sudo -v

package:
	@./scripts/package.sh

redeploy-mochame: auth-sudo
	@./scripts/redeploy-mochame.sh

redeploy-server:
	@./scripts/redeploy-server.sh

debug:
	@./scripts/debug-run.sh

fresh: auth-sudo package redeploy-mochame

fresh-all: auth-sudo package redeploy-server redeploy-mochame
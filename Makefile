NIX_SHELL := nix develop --command

.PHONY: help compile link build build-all run-headless vm test unit-test vm-test ipc-test multi-monitor-test idle-lock-test clean

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-15s\033[0m %s\n", $$1, $$2}'

compile: ## Scala compilation
	$(NIX_SHELL) bash -c "mill shutdown 2>/dev/null; mill compositor.compile"

link: ## LLVM native linking (compositor + swcmsg)
	$(NIX_SHELL) bash -c "mill shutdown 2>/dev/null; mill compositor.nativeLink; mill swcmsg.nativeLink"

build: compile link ## Compile + link

run-headless: build ## Run compositor headless (no display needed)
	XDG_RUNTIME_DIR=$$(mktemp -d) WLR_BACKENDS=headless WLR_RENDERER=pixman \
		./out/compositor/nativeLink.dest/out

vm: build ## Launch interactive VM with QEMU
	nix run --impure .#vm

unit-test: ## Run pure-core JVM tests (fast, no C deps)
	$(NIX_SHELL) bash -c "mill shutdown 2>/dev/null; mill core.jvm.test"

vm-test: build ## Run NixOS VM compositor test
	nix build --impure .#checks.x86_64-linux.compositor-vm-test --print-build-logs

ipc-test: build ## Run NixOS VM IPC integration test
	nix build --impure .#checks.x86_64-linux.ipc-test --print-build-logs

multi-monitor-test: build ## Run NixOS VM multi-monitor test (2 outputs)
	nix build --impure .#checks.x86_64-linux.multi-monitor-test --print-build-logs

idle-lock-test: build ## Run NixOS VM idle auto-lock (exit-to-getty) test
	nix build --impure .#checks.x86_64-linux.idle-lock-test --print-build-logs

test: unit-test vm-test ipc-test ## Run all tests

clean: ## Remove build artifacts
	rm -rf out

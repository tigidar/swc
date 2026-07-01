{ pkgs, compositorPackage, swcmsgPackage, ... }:

# Idle auto-lock ("exit-to-getty" lock) end-to-end test.
#
# Models the PRODUCTION launch model (not the systemd-service model used by the
# other checks): getty on tty1 with NO autologin, a real password-gated login,
# and a login-shell that `exec`s the compositor. This is the load-bearing
# security property of the feature — when the compositor exits on idle, the
# session must fall back to the password prompt, not a dead tty or an autostart.
#
# Verifies, in order:
#   1. Boot is GATED   — compositor not running; agetty login: prompt on tty1.
#   2. Login starts it — typing the password on tty1 launches the compositor.
#   3. Idle LOCKS      — no input for COMPOSITOR_IDLE_SECONDS → compositor exits
#                        → agetty login: prompt reappears on tty1 (gate restored).
#   4. Activity resets — continuous input keeps the compositor alive past the
#                        timeout; stopping input lets it exit again.
#
# The test driver's `succeed`/`fail` run on the backdoor root shell (always
# available, independent of tty1). `send_key` injects QEMU keycodes into the
# active VT (tty1), simulating the real user at the keyboard.

let
  idleSeconds = 6;

  # Login-shell launcher, mirroring /p/cfg/nixos/modules/swc.nix's start-swc.
  startSwc = pkgs.writeShellScript "start-swc" ''
    export XDG_RUNTIME_DIR=/run/user/$(id -u)
    export WLR_RENDERER=pixman
    export WLR_LOG=info
    export COMPOSITOR_IDLE_SECONDS=${toString idleSeconds}
    exec ${compositorPackage}/bin/compositor 2> "$XDG_RUNTIME_DIR/compositor.log"
  '';
in
pkgs.testers.runNixOSTest {
  name = "idle-lock-test";

  nodes.machine = { lib, pkgs, ... }: {
    virtualisation.qemu.options = [
      "-vga" "none"
      "-device" "virtio-gpu-pci"
      "-device" "virtio-keyboard-pci"
    ];
    virtualisation.memorySize = 2048;

    hardware.graphics.enable = true;

    environment.systemPackages = [ compositorPackage swcmsgPackage ];
    fonts.packages = [ pkgs.dejavu_fonts ];

    # Real password gate: getty on tty1, NO autologin, password-protected user.
    services.getty.autologinUser = lib.mkForce null;

    users.users.testuser = {
      isNormalUser = true;
      uid = 1000;
      password = "test";
      extraGroups = [ "video" "input" "render" ];
    };

    # Login-shell init: on tty1, with no Wayland session yet, exec the
    # compositor. When it exits the shell is gone (exec'd), the login session
    # ends, and getty@tty1 respawns the login: prompt — the lock.
    environment.loginShellInit = ''
      if [ "$(tty)" = /dev/tty1 ] && [ -z "$WAYLAND_DISPLAY" ]; then
        exec ${startSwc}
      fi
    '';
  };

  testScript = ''
    import time

    def diag(label):
      machine.log(f"=== DIAG: {label} ===")
      machine.log("procs:\n" + machine.succeed(
        "ps -eo pid,tty,stat,comm | grep -Ei 'agetty|login|bash|compositor|foot' || true"))
      machine.log("active VT: " + machine.succeed("cat /sys/class/tty/tty0/active || true"))
      machine.log("agetty: " + machine.succeed("pgrep -af agetty || true"))
      machine.log("sessions:\n" + machine.succeed("loginctl list-sessions --no-pager || true"))

    def type_string(s):
      for ch in s:
        machine.send_key(ch)
        time.sleep(0.15)

    def login_on_tty1():
      # Dismiss any pending prompt, then enter credentials at the login: gate.
      machine.send_key("ret")
      machine.sleep(1)
      type_string("testuser")
      machine.send_key("ret")
      machine.sleep(2)
      type_string("test")
      machine.send_key("ret")
      # Compositor up == login succeeded AND the login shell exec'd start-swc.
      machine.wait_until_succeeds("test -S /run/user/1000/wayland-0", timeout=60)

    machine.wait_for_unit("multi-user.target")
    machine.wait_for_unit("getty@tty1.service")
    machine.sleep(3)

    # ── 1. Boot is gated behind the password prompt ──────────────────
    machine.fail("pgrep -x compositor")
    machine.succeed("pgrep -af agetty | grep -qw tty1")
    diag("boot (expect: no compositor, agetty on tty1)")
    machine.log("Boot is gated: no compositor, login: prompt on tty1")

    # ── 2. Logging in starts the compositor ──────────────────────────
    login_on_tty1()
    machine.succeed("pgrep -x compositor")
    diag("after-login (expect: compositor running, owns active VT)")
    machine.log("Login succeeded; compositor is running")

    # ── 3. Idle exceeds the timeout → compositor exits → gate restored ─
    # No input is injected; checkIdle should fire ~${toString idleSeconds}s
    # after the last activity (the login keystrokes).
    machine.wait_until_fails("pgrep -x compositor", timeout=30)
    machine.log("Compositor exited after idle timeout (the lock)")
    machine.wait_until_succeeds("pgrep -af agetty | grep -qw tty1", timeout=30)
    machine.succeed("test ! -S /run/user/1000/wayland-0")
    diag("after-idle (expect: no compositor, agetty back on tty1)")
    machine.log("Password prompt restored on tty1; Wayland socket gone")

    # ── 4. Continuous activity resets the idle timer ─────────────────
    login_on_tty1()
    machine.log("Logged back in; injecting input across > the idle timeout")
    # Confirm the compositor actually RECEIVES the injected keys (rules out a
    # wrong-VT focus problem) by counting onKeyboardKey log lines before/after.
    count_keys = "awk '/onKeyboardKey/{c++} END{print c+0}' /run/user/1000/compositor.log"
    keys_before = int(machine.succeed(count_keys))
    # Inject a keystroke every 1.5s for ~13.5s (>> ${toString idleSeconds}s).
    # Each key resets lastActivityMs, so the deadline never elapses.
    for _ in range(9):
      machine.send_key("shift")
      time.sleep(1.5)
    keys_after = int(machine.succeed(count_keys))
    machine.log(f"compositor key events: before={keys_before} after={keys_after}")
    assert keys_after > keys_before, "compositor received no injected keys (wrong-VT focus?)"
    machine.succeed("pgrep -x compositor")
    diag("during-activity (expect: compositor alive, received keys)")
    machine.log("Compositor still alive after sustained input — timer resets work")

    # Stop input → it must exit again within the timeout.
    machine.wait_until_fails("pgrep -x compositor", timeout=30)
    machine.wait_until_succeeds("pgrep -af agetty | grep -qw tty1", timeout=30)
    machine.log("All idle-lock tests passed!")
  '';
}

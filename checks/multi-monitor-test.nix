{ pkgs, compositorPackage, swcmsgPackage, ... }:

let
  compositorLauncher = pkgs.writeShellScript "start-compositor" ''
    export XDG_RUNTIME_DIR=/run/user/1000
    export WLR_RENDERER=pixman
    export WLR_LOG=error
    export SWC_HEADLESS_OUTPUTS=2
    export COMPOSITOR_AUTOSTART=1
    export COMPOSITOR_TERM="${pkgs.foot}/bin/foot"
    exec ${compositorPackage}/bin/compositor
  '';

  swcmsg = pkgs.writeShellScript "swcmsg-wrapper" ''
    export XDG_RUNTIME_DIR=/run/user/1000
    exec ${swcmsgPackage}/bin/swcmsg "$@"
  '';
in
pkgs.testers.runNixOSTest {
  name = "multi-monitor-test";

  nodes.machine = { pkgs, ... }: {
    # No GPU needed — compositor uses headless backend with SWC_HEADLESS_OUTPUTS=2
    virtualisation.qemu.options = [
      "-vga" "none"
    ];
    virtualisation.memorySize = 2048;

    hardware.graphics.enable = true;

    environment.systemPackages = [
      compositorPackage
      swcmsgPackage
      pkgs.foot
      pkgs.wayland-utils
    ];

    fonts.packages = [ pkgs.dejavu_fonts ];

    users.users.testuser = {
      isNormalUser = true;
      uid = 1000;
      extraGroups = [ "video" "input" ];
    };

    systemd.tmpfiles.rules = [ "d /run/user/1000 0700 testuser testuser -" ];

    security.pam.services.compositor = {
      startSession = true;
    };

    systemd.services.compositor = {
      description = "Scala Compositor (multi-monitor)";
      after = [ "systemd-user-sessions.service" ];
      conflicts = [ "getty@tty1.service" ];
      wantedBy = [ "graphical.target" ];

      serviceConfig = {
        ExecStart = compositorLauncher;
        User = "testuser";
        TTYPath = "/dev/tty1";
        TTYReset = "yes";
        TTYVHangup = "yes";
        TTYVTDisallocate = "yes";
        StandardInput = "tty-force";
        StandardOutput = "journal";
        StandardError = "journal";
        PAMName = "compositor";
        Restart = "on-failure";
        RestartSec = "1";
      };
    };

    systemd.targets.graphical = {
      wantedBy = [ "multi-user.target" ];
    };
  };

  testScript = ''
    import json

    machine.wait_for_unit("multi-user.target")
    machine.sleep(5)

    # ── Verify compositor is running ─────────────────────────────────
    machine.succeed("pgrep compositor")
    machine.succeed("test -S /run/user/1000/wayland-0")
    machine.succeed("test -S /run/user/1000/swc.sock")
    machine.log("Compositor and IPC socket are up")

    # ── Verify two outputs detected ──────────────────────────────────
    output = machine.succeed("su - testuser -c '${swcmsg} list-outputs'")
    machine.log(f"list-outputs: {output}")
    parsed = json.loads(output)
    outputs = parsed.get("outputs", [])
    assert len(outputs) >= 2, f"Expected >= 2 outputs, got {len(outputs)}: {output}"
    machine.log(f"Detected {len(outputs)} outputs: {[o['name'] for o in outputs]}")

    # First output should be focused
    focused = [o for o in outputs if o.get("focused")]
    assert len(focused) == 1, f"Expected exactly 1 focused output, got {len(focused)}"
    machine.log(f"Focused output: {focused[0]['name']}")

    # ── Verify auto-started terminal on focused output ───────────────
    machine.succeed("pgrep foot")
    output = machine.succeed("su - testuser -c '${swcmsg} list-windows'")
    parsed = json.loads(output)
    windows = parsed.get("windows", [])
    mapped = [w for w in windows if w.get("mapped")]
    assert len(mapped) >= 1, f"Expected >= 1 mapped window, got: {output}"
    machine.log(f"Mapped windows: {len(mapped)}")

    # ── Test get-focused-output ──────────────────────────────────────
    output = machine.succeed("su - testuser -c '${swcmsg} get-focused-output'")
    parsed = json.loads(output)
    assert parsed.get("name") is not None, f"Expected focused output name, got: {output}"
    machine.log(f"get-focused-output: {parsed.get('name')}")

    # ── Test workspace switching ─────────────────────────────────────
    # Switch to workspace 2 (should hide the terminal)
    output = machine.succeed("su - testuser -c '${swcmsg} switch-workspace 2'")
    assert '"type":"ok"' in output, f"Expected ok, got: {output}"
    machine.log("Switched to workspace 2")

    # Verify active workspace changed
    output = machine.succeed("su - testuser -c '${swcmsg} list-outputs'")
    parsed = json.loads(output)
    focused_out = [o for o in parsed["outputs"] if o.get("focused")][0]
    assert focused_out["activeWorkspace"] == 2, f"Expected workspace 2, got: {focused_out['activeWorkspace']}"
    machine.log("Workspace 2 is active")

    # Switch back to workspace 1
    machine.succeed("su - testuser -c '${swcmsg} switch-workspace 1'")
    machine.log("Switched back to workspace 1")

    # ── Test focus-output ────────────────────────────────────────────
    # Focus second output (index 1)
    output = machine.succeed("su - testuser -c '${swcmsg} focus-output 1'")
    assert '"type":"ok"' in output, f"Expected ok, got: {output}"

    output = machine.succeed("su - testuser -c '${swcmsg} get-focused-output'")
    parsed = json.loads(output)
    machine.log(f"After focus-output 1: focused = {parsed.get('name')}")

    # Focus back to first output (index 0)
    machine.succeed("su - testuser -c '${swcmsg} focus-output 0'")
    machine.log("Focused back to output 0")

    # ── Test move-to-workspace ───────────────────────────────────────
    # Move the terminal to workspace 3
    output = machine.succeed("su - testuser -c '${swcmsg} move-to-workspace 3'")
    assert '"type":"ok"' in output, f"Expected ok, got: {output}"
    machine.log("Moved window to workspace 3")

    # Switch to workspace 3 to verify the window is there
    machine.succeed("su - testuser -c '${swcmsg} switch-workspace 3'")
    output = machine.succeed("su - testuser -c '${swcmsg} list-windows'")
    parsed = json.loads(output)
    mapped = [w for w in parsed.get("windows", []) if w.get("mapped")]
    assert len(mapped) >= 1, f"Expected window on workspace 3, got: {output}"
    machine.log("Window found on workspace 3")

    # ── Cleanup ──────────────────────────────────────────────────────
    machine.succeed("su - testuser -c '${swcmsg} exit'")
    machine.sleep(2)
    status, _ = machine.execute("pgrep compositor")
    assert status != 0, "Compositor should have exited"
    machine.log("All multi-monitor tests passed!")
  '';
}

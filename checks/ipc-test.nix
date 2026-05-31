{ pkgs, compositorPackage, swcmsgPackage, ... }:

let
  compositorLauncher = pkgs.writeShellScript "start-compositor" ''
    export XDG_RUNTIME_DIR=/run/user/1000
    export WLR_RENDERER=pixman
    export WLR_LOG=error
    export COMPOSITOR_AUTOSTART=1
    export COMPOSITOR_TERM="${pkgs.foot}/bin/foot"
    exec ${compositorPackage}/bin/compositor
  '';

  # Helper script that runs swcmsg with the right env
  swcmsg = pkgs.writeShellScript "swcmsg-wrapper" ''
    export XDG_RUNTIME_DIR=/run/user/1000
    exec ${swcmsgPackage}/bin/swcmsg "$@"
  '';
in
pkgs.testers.runNixOSTest {
  name = "ipc-test";

  nodes.machine = { pkgs, ... }: {
    virtualisation.qemu.options = [
      "-vga" "none"
      "-device" "virtio-gpu-pci"
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
      description = "Scala Compositor";
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
    machine.wait_for_unit("multi-user.target")
    machine.sleep(5)

    # Verify compositor and IPC socket are running
    machine.succeed("pgrep compositor")
    machine.succeed("test -S /run/user/1000/wayland-0")
    machine.succeed("test -S /run/user/1000/swc.sock")
    machine.log("Compositor and IPC socket are up")

    # ── IPC: list-windows ──────────────────────────────────────────
    # The auto-started foot terminal should appear
    machine.succeed("pgrep foot")
    output = machine.succeed("su - testuser -c '${swcmsg} list-windows'")
    assert '"type":"windows-listed"' in output, f"Expected windows-listed response, got: {output}"
    assert '"mapped":true' in output, f"Expected at least one mapped window, got: {output}"
    machine.log(f"list-windows: {output}")

    # ── IPC: get-focused ───────────────────────────────────────────
    output = machine.succeed("su - testuser -c '${swcmsg} get-focused'")
    assert '"type":"focused-window"' in output, f"Expected focused-window response, got: {output}"
    assert '"id":' in output, f"Expected an id field, got: {output}"
    machine.log(f"get-focused: {output}")

    # ── IPC: spawn a second terminal ───────────────────────────────
    machine.succeed("su - testuser -c '${swcmsg} spawn ${pkgs.foot}/bin/foot'")
    machine.sleep(2)

    # Verify two foot processes and two windows
    output = machine.succeed("su - testuser -c '${swcmsg} list-windows'")
    # Count mapped windows
    import json
    parsed = json.loads(output)
    mapped = [w for w in parsed.get("windows", []) if w.get("mapped")]
    assert len(mapped) >= 2, f"Expected >= 2 mapped windows, got {len(mapped)}: {output}"
    machine.log(f"After spawn, {len(mapped)} mapped windows")

    # ── IPC: layout set-master-ratio ───────────────────────────────
    output = machine.succeed("su - testuser -c '${swcmsg} layout set-master-ratio 0.7'")
    assert '"type":"ok"' in output, f"Expected ok response, got: {output}"
    machine.log("set-master-ratio 0.7: ok")

    # ── IPC: layout set-master-count ───────────────────────────────
    output = machine.succeed("su - testuser -c '${swcmsg} layout set-master-count 2'")
    assert '"type":"ok"' in output, f"Expected ok response, got: {output}"
    machine.log("set-master-count 2: ok")

    # ── IPC: close-focused ─────────────────────────────────────────
    output = machine.succeed("su - testuser -c '${swcmsg} close-focused'")
    assert '"type":"ok"' in output, f"Expected ok response, got: {output}"
    machine.sleep(1)
    machine.log("close-focused: ok")

    # ── IPC: exit ──────────────────────────────────────────────────
    machine.succeed("su - testuser -c '${swcmsg} exit'")
    machine.sleep(2)

    # Compositor should have terminated
    status, _ = machine.execute("pgrep compositor")
    assert status != 0, "Compositor should have exited after IPC exit command"
    machine.log("IPC exit confirmed — compositor terminated")

    machine.log("All IPC tests passed!")
  '';
}

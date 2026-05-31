{ pkgs, compositorPackage, swcmsgPackage, ... }:

let
  compositorLauncher = pkgs.writeShellScript "start-compositor" ''
    export XDG_RUNTIME_DIR=/run/user/1000
    export WLR_RENDERER=pixman
    export WLR_LOG=error
    export COMPOSITOR_AUTOSTART=1
    export COMPOSITOR_TERM="${pkgs.foot}/bin/foot"
    exec ${compositorPackage}/bin/compositor 2>/run/user/1000/compositor.log
  '';

  swcmsg = pkgs.writeShellScript "swcmsg-wrapper" ''
    export XDG_RUNTIME_DIR=/run/user/1000
    exec ${swcmsgPackage}/bin/swcmsg "$@"
  '';
in
pkgs.testers.runNixOSTest {
  name = "keyboard-test";

  nodes.machine = { pkgs, ... }: {
    virtualisation.qemu.options = [
      "-vga" "none"
      "-device" "virtio-gpu-pci"
      # Add a second USB keyboard so compositor sees 2 keyboard devices
      "-device" "usb-kbd,id=kbd2"
    ];
    virtualisation.memorySize = 2048;

    hardware.graphics.enable = true;

    environment.systemPackages = [
      compositorPackage
      swcmsgPackage
      pkgs.foot
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
      description = "Scala Compositor (keyboard test)";
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
    import re

    machine.wait_for_unit("multi-user.target")
    machine.sleep(5)

    # ── Verify compositor is running ─────────────────────────────────
    machine.succeed("pgrep compositor")
    machine.succeed("test -S /run/user/1000/wayland-0")
    machine.succeed("test -S /run/user/1000/swc.sock")
    machine.log("Compositor and IPC socket are up")

    # ── Check what input devices Linux sees ──────────────────────────
    inputs = machine.succeed("cat /proc/bus/input/devices")
    machine.log(f"Input devices:\n{inputs}")

    # ── Check compositor log file for keyboard registrations ─────────
    comp_log = machine.succeed("cat /run/user/1000/compositor.log")
    machine.log(f"Compositor log:\n{comp_log}")

    # Find all "new keyboard registered" lines and extract pointers
    kb_lines = [l for l in comp_log.splitlines() if "new keyboard registered" in l]
    machine.log(f"Keyboard registration lines ({len(kb_lines)}): {kb_lines}")
    assert len(kb_lines) >= 2, f"Expected >= 2 keyboard registrations, got {len(kb_lines)}"

    # Extract kb= pointer addresses
    kb_ptrs = set()
    for line in kb_lines:
      m = re.search(r'kb=(0x[0-9a-f]+)', line)
      if m:
        kb_ptrs.add(m.group(1))
    machine.log(f"Distinct keyboard pointers: {kb_ptrs}")
    assert len(kb_ptrs) >= 2, f"Expected >= 2 distinct keyboard pointers, got {len(kb_ptrs)}: {kb_ptrs}"

    # ── Send a keystroke via QEMU monitor ────────────────────────────
    machine.send_key("a")
    machine.sleep(1)

    # ── Verify KbCtx was used to resolve keyboard ────────────────────
    comp_log2 = machine.succeed("cat /run/user/1000/compositor.log")
    kbctx_lines = [l for l in comp_log2.splitlines() if "onKeyboardKey" in l and "from KbCtx" in l]
    machine.log(f"KbCtx key event lines ({len(kbctx_lines)}): {kbctx_lines}")
    assert len(kbctx_lines) >= 1, f"Expected >= 1 key event using KbCtx, got {len(kbctx_lines)}"

    # Verify the keyboard pointer in the key event matches one of the registered keyboards
    for line in kbctx_lines:
      m = re.search(r'kb=(0x[0-9a-f]+)', line)
      if m:
        assert m.group(1) in kb_ptrs, f"Key event used kb={m.group(1)} which was not in registered set {kb_ptrs}"
    machine.log("Key events correctly resolved keyboard via KbCtx")

    # ── Verify compositor still works after keyboard events ──────────
    output = machine.succeed("su - testuser -c '${swcmsg} list-windows'")
    parsed = json.loads(output)
    machine.log(f"Windows after key events: {parsed}")

    # ── Cleanup ──────────────────────────────────────────────────────
    machine.succeed("su - testuser -c '${swcmsg} exit'")
    machine.sleep(2)
    status, _ = machine.execute("pgrep compositor")
    assert status != 0, "Compositor should have exited"
    machine.log("All keyboard tests passed!")
  '';
}

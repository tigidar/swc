{ pkgs, compositorPackage, ... }:

let
  compositorLauncher = pkgs.writeShellScript "start-compositor" ''
    export XDG_RUNTIME_DIR=/run/user/1000
    export WLR_RENDERER=pixman
    export WLR_LOG=debug
    export COMPOSITOR_AUTOSTART=1
    export COMPOSITOR_TERM="${pkgs.foot}/bin/foot"
    exec ${compositorPackage}/bin/compositor
  '';
in
pkgs.testers.runNixOSTest {
  name = "compositor-vm";

  nodes.machine = { pkgs, ... }: {
    virtualisation.qemu.options = [
      "-vga" "none"
      "-device" "virtio-gpu-pci"
    ];
    virtualisation.memorySize = 2048;

    hardware.graphics.enable = true;

    environment.systemPackages = [
      compositorPackage
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

    # Minimal PAM config (avoids broken pam_lastlog2 in test VM)
    security.pam.services.compositor = {
      startSession = true;  # creates logind session
    };

    # Compositor as a display-manager-like service on tty1
    systemd.services.compositor = {
      description = "Scala Compositor";
      after = [ "systemd-user-sessions.service" ];
      conflicts = [ "getty@tty1.service" ];
      wantedBy = [ "graphical.target" ];

      serviceConfig = {
        ExecStart = compositorLauncher;
        User = "testuser";

        # Attach to real VT for DRM session via logind
        TTYPath = "/dev/tty1";
        TTYReset = "yes";
        TTYVHangup = "yes";
        TTYVTDisallocate = "yes";
        StandardInput = "tty-force";
        StandardOutput = "journal";
        StandardError = "journal";

        # Custom PAM creates a logind session without faulty modules
        PAMName = "compositor";

        Restart = "on-failure";
        RestartSec = "1";
      };
    };

    # Need graphical.target for wantedBy
    systemd.targets.graphical = {
      wantedBy = [ "multi-user.target" ];
    };
  };

  testScript = ''
    machine.wait_for_unit("multi-user.target")
    machine.sleep(5)

    # Verify compositor is running on DRM
    machine.succeed("pgrep compositor")
    machine.succeed("test -S /run/user/1000/wayland-0")

    # Verify Wayland globals
    output = machine.succeed(
      "su - testuser -c 'XDG_RUNTIME_DIR=/run/user/1000 WAYLAND_DISPLAY=wayland-0 wayland-info'"
    )
    assert "wl_compositor" in output, "wl_compositor not found"
    assert "wl_seat" in output, "wl_seat not found"
    assert "xdg_wm_base" in output, "xdg_wm_base not found"
    assert "wl_shm" in output, "wl_shm not found"
    assert "zwlr_layer_shell_v1" in output, "zwlr_layer_shell_v1 not found"

    # Wait for foot terminal to render
    machine.sleep(3)

    # Verify foot is running and window was mapped
    machine.succeed("pgrep foot")
    machine.log("Compositor and terminal verified!")

    # Take screenshot proving visual output
    machine.screenshot("compositor_screenshot")
    machine.log("Screenshot captured!")
  '';
}

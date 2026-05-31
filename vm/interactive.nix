{ pkgs, compositorPackage, swcmsgPackage, ... }:

# Interactive VM that boots directly into the compositor.
# Keybindings:
#   Super+Return    = open terminal
#   Super+Q         = close focused window
#   Super+Escape    = exit compositor
#   Super+H/L       = decrease/increase master ratio
#   Super+Shift+H/L = decrease/increase master count
# IPC:
#   swcmsg list-windows
#   swcmsg layout set-master-ratio 0.6

let
  compositorLauncher = pkgs.writeShellScript "start-compositor" ''
    export XDG_RUNTIME_DIR=/run/user/1000
    export WLR_RENDERER=pixman
    export WLR_LOG=debug
    export COMPOSITOR_AUTOSTART=1
    export COMPOSITOR_TERM="${pkgs.foot}/bin/foot"
    exec ${compositorPackage}/bin/compositor
  '';

  nixos = pkgs.nixos ({ pkgs, lib, ... }: {
    system.stateVersion = "24.11";
    networking.hostName = "compositor-vm";

    # Enable graphics
    hardware.graphics.enable = true;

    # Install packages
    environment.systemPackages = with pkgs; [
      compositorPackage
      swcmsgPackage
      foot
      wayland-utils
      htop
    ];

    # Create user
    users.users.user = {
      isNormalUser = true;
      password = "user";
      uid = 1000;
      extraGroups = [ "video" "input" "render" ];
    };

    # Ensure XDG_RUNTIME_DIR exists
    systemd.tmpfiles.rules = [
      "d /run/user/1000 0700 user user -"
    ];

    # PAM service for logind session (same pattern as the working test VM)
    security.pam.services.compositor = {
      startSession = true;
    };

    # Compositor as a display-manager-like service on tty1
    # Needs TTY + PAM for DRM access via logind
    systemd.services.compositor = {
      description = "Scala Native Compositor";
      after = [ "systemd-user-sessions.service" ];
      conflicts = [ "getty@tty1.service" ];
      wantedBy = [ "graphical.target" ];

      serviceConfig = {
        ExecStart = compositorLauncher;
        User = "user";

        # Attach to VT for DRM session via logind
        TTYPath = "/dev/tty1";
        TTYReset = "yes";
        TTYVHangup = "yes";
        TTYVTDisallocate = "yes";
        StandardInput = "tty-force";
        StandardOutput = "journal+console";
        StandardError = "journal+console";

        # PAM creates a logind session → DRM access
        PAMName = "compositor";

        Restart = "on-failure";
        RestartSec = "1";
      };
    };

    # Need graphical.target for wantedBy
    systemd.targets.graphical = {
      wantedBy = [ "multi-user.target" ];
    };

    # Launch extra terminals so tiling is visible immediately
    systemd.services.extra-terminals = {
      description = "Spawn extra terminals for tiling demo";
      after = [ "compositor.service" ];
      requires = [ "compositor.service" ];
      wantedBy = [ "graphical.target" ];
      serviceConfig = {
        Type = "oneshot";
        User = "user";
        Environment = [
          "XDG_RUNTIME_DIR=/run/user/1000"
          "WAYLAND_DISPLAY=wayland-0"
        ];
        ExecStartPre = "${pkgs.coreutils}/bin/sleep 3";
        ExecStart = pkgs.writeShellScript "spawn-terminals" ''
          ${pkgs.foot}/bin/foot &
          sleep 0.5
          ${pkgs.foot}/bin/foot &
        '';
        RemainAfterExit = true;
      };
    };

    # Font for terminal
    fonts.packages = with pkgs; [
      dejavu_fonts
      liberation_ttf
    ];

    # Send kernel + service logs to serial console (visible on host terminal)
    boot.kernelParams = [ "console=ttyS0,115200" ];
  });

  # The VM runner script with QEMU flags for display
  vmScript = pkgs.writeShellScriptBin "run-compositor-vm" ''
    exec ${nixos.config.system.build.vm}/bin/run-compositor-vm-vm \
      -m 2048 \
      -smp 2 \
      -vga none \
      -device virtio-gpu-pci \
      -device virtio-keyboard-pci \
      -device virtio-mouse-pci \
      -display gtk,gl=on \
      -serial stdio \
      "$@"
  '';
in
  vmScript

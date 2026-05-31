{
  description = "Scala Native Wayland Compositor";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = nixpkgs.legacyPackages.${system};
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        name = "compositor-dev";

        nativeBuildInputs = with pkgs; [
          # Build tools
          mill
          scala-cli
          clang
          pkg-config

          # Scala Native
          openjdk

          # wlroots and Wayland
          wlroots_0_18
          wayland
          wayland-protocols
          wayland-scanner
          libxkbcommon
          pixman
          libdrm
          mesa # EGL, GLES
          libinput
          udev

          # Testing tools
          wayland-utils # wayland-info
          wlr-randr
          foot # lightweight terminal
          grim # screenshots
          wlcs
        ];

        # Help clang find system headers
        LIBCLANG_PATH = "${pkgs.llvmPackages.libclang.lib}/lib";

        shellHook = ''
          echo "compositor-dev shell"
          echo "  wlroots: $(pkg-config --modversion wlroots-0.18)"
          echo "  wayland: $(pkg-config --modversion wayland-server)"
          echo "  mill:    $(mill version 2>/dev/null || echo 'available')"
        '';
      };

      checks.${system} = {
        compositor-vm-test = import ./checks/compositor-test.nix {
          inherit pkgs;
          compositorPackage = self.packages.${system}.default;
        };
        ipc-test = import ./checks/ipc-test.nix {
          inherit pkgs;
          compositorPackage = self.packages.${system}.default;
          swcmsgPackage = self.packages.${system}.swcmsg;
        };
        multi-monitor-test = import ./checks/multi-monitor-test.nix {
          inherit pkgs;
          compositorPackage = self.packages.${system}.default;
          swcmsgPackage = self.packages.${system}.swcmsg;
        };
        keyboard-test = import ./checks/keyboard-test.nix {
          inherit pkgs;
          compositorPackage = self.packages.${system}.default;
          swcmsgPackage = self.packages.${system}.swcmsg;
        };
      };

      packages.${system} =
        let
          # The binary is built by Mill outside Nix (make build), so we need
          # --impure to reference paths outside the flake source.
          swcRoot = toString ./.;
          binaryPath = builtins.path {
            path = /. + "${swcRoot}/out/compositor/nativeLink.dest/out";
            name = "compositor-binary";
          };
          swcmsgBinaryPath = builtins.path {
            path = /. + "${swcRoot}/out/swcmsg/nativeLink.dest/out";
            name = "swcmsg-binary";
          };
          compositorPkg = pkgs.stdenv.mkDerivation {
            pname = "scala-compositor";
            version = "0.0.1";
            dontUnpack = true;
            nativeBuildInputs = [ pkgs.autoPatchelfHook ];
            buildInputs = with pkgs; [
              wlroots_0_18 wayland libxkbcommon pixman libdrm mesa libinput udev
              seatd  # libseat for session management (DRM access)
            ];
            installPhase = ''
              mkdir -p $out/bin
              cp ${binaryPath} $out/bin/compositor
              chmod +x $out/bin/compositor
            '';
          };
          swcmsgPkg = pkgs.stdenv.mkDerivation {
            pname = "swcmsg";
            version = "0.0.1";
            dontUnpack = true;
            nativeBuildInputs = [ pkgs.autoPatchelfHook ];
            buildInputs = with pkgs; [ zlib ];
            installPhase = ''
              mkdir -p $out/bin
              cp ${swcmsgBinaryPath} $out/bin/swcmsg
              chmod +x $out/bin/swcmsg
            '';
          };
        in {
          default = compositorPkg;
          swcmsg = swcmsgPkg;

          # Interactive VM — boots into the compositor
          vm = import ./vm/interactive.nix {
            inherit pkgs;
            compositorPackage = compositorPkg;
            swcmsgPackage = swcmsgPkg;
          };
        };

      # Convenience app for `nix run .#vm`
      apps.${system}.vm = {
        type = "app";
        program = "${self.packages.${system}.vm}/bin/run-compositor-vm";
      };
    };
}

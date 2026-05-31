# swc — Scala Native Wayland Compositor

A Wayland compositor written in Scala 3 and Scala Native, built on top of
[wlroots](https://gitlab.freedesktop.org/wlroots/wlroots) 0.18. swc compiles
to a native binary via LLVM and links directly against wlroots, handling
display outputs, xdg-shell windows, keyboard and pointer input,
master-stack tiling, and scene-graph rendering. External tools can query
and control the compositor over a JSON IPC socket.

> ## 🚧 UNDER CONSTRUCTION 🚧
>
> **This project is a work in progress and is not yet usable.** Nothing here is
> guaranteed to work, compile, or stay stable. APIs, types, and documentation
> may change or disappear without notice, and any published coordinates below
> are **not yet released**. Do not depend on this project yet — treat everything
> in this README as a statement of intent, not a promise.

---

## Status

Early-stage and exploratory. swc is a working compositor — it boots, tiles
windows, handles input, renders, and accepts IPC commands — but it is not
yet a daily driver. There is no stability guarantee on the IPC wire format,
the keybinding set is minimal, and many features that mature compositors
ship (drag-to-move, fullscreen, layer-shell, screencopy, gamma control
protocols) are not yet implemented. Treat it as a serious experiment in
applying Scala 3 to a domain that has historically been the exclusive
province of C, C++, and Rust.

---

## Why Scala Native?

Wayland compositors have been written in C (Sway, dwl, river), C++
(KWin, Hyprland) and Rust (Smithay, niri, cosmic-comp). Scala 3 + Scala
Native is unexplored territory in this space, and offers a different
trade-off curve:

- **LLVM-backed native compilation.** Performance comparable to C on the
  hot path, no JVM, no GC required for the render loop.
- **Scala 3 type system.** Algebraic data types, opaque types, given
  instances, and inline metaprogramming — useful for modelling the
  Wayland protocol and compositor state with compile-time guarantees.
- **Explicit unsafe escape hatches.** Pointer arithmetic, manual
  allocation, and C interop are first-class when you need them.
- **Pure logic on the JVM.** The functional core (layout, damage,
  input routing, IPC codec) is cross-compiled to the JVM and tested
  with munit and ScalaCheck, with zero native dependencies.

wlroots provides the hard substrate — DRM/KMS, libinput, buffer
management, protocol implementations — so swc focuses on window
management, layout, and policy rather than re-implementing display
plumbing.

---

## Features

What works today:

- **Output management** — single and multi-monitor via
  `wlr_output_layout`, with preferred-mode selection and frame
  callbacks driving the scene graph.
- **xdg-shell toplevels** — application windows map, configure,
  unmap, and destroy through standard xdg-shell.
- **Master-stack tiling** — one master area on the left, stacked
  windows on the right. Master ratio and count are runtime
  adjustable.
- **Keyboard input** — xkbcommon-based keymap, configurable repeat
  rate, compositor keybindings, and per-surface key dispatch via
  `wl_seat`.
- **Pointer input** — cursor motion, scene-graph hit testing,
  click-to-focus, and per-surface pointer event dispatch.
- **Scene-graph rendering** — `wlr_scene` retained-mode rendering,
  automatic damage tracking, and frame pacing locked to display
  refresh.
- **Process spawning** — Super+Return forks a terminal
  (`foot` by default, override with `COMPOSITOR_TERM`).
- **JSON IPC** — Unix domain socket at
  `$XDG_RUNTIME_DIR/swc.sock` integrated with the Wayland event
  loop. Commands and responses are newline-delimited JSON with
  type-safe codecs derived by jsoniter-scala.
- **swcmsg CLI** — companion command-line tool for sending IPC
  requests: list windows, get/close focused, spawn, adjust layout,
  exit the compositor.
- **Reproducible builds** — Nix flake pinning wlroots 0.18 and the
  full native dependency set.
- **VM-based integration tests** — full NixOS VM checks for the
  compositor lifecycle, IPC, multi-monitor, and keyboard handling.

---

## Architecture

swc is organised as three Mill modules, with a pure functional core,
an FFI-bound imperative shell, and a small CLI client.

### Modules

```
core ────────┐
             ├── pure Scala 3 — cross-compiled to JVM and Native
             │   geometry, input, windows, layout, IPC codec
             │
compositor ──┤── Scala Native — depends on core.native
             │   wlroots FFI, signal/listener wiring, render loop,
             │   IPC server bound to wl_event_loop
             │
swcmsg ──────┘── Scala Native — depends on core.native
                 CLI client: connect to socket, send command, print
                 response, exit
```

- **`core`** — zero C/FFI dependencies. Contains the algebra:
  `Rect`, `Vec2`, `DamageRegion`, `KeyBindingMap`, `WindowList`,
  `FocusModel`, `MasterStackLayout`, `IpcCommand`, `IpcResponse`,
  and the pure dispatch functions that turn `(state, command)`
  into `(state, response, effect)`. Cross-compiled to JVM for
  testing and Native for linking into the compositor.

- **`compositor`** — the imperative shell. Scala Native module
  containing `compositor.Main`, FFI bindings under `compositor.ffi`,
  and a thin C bridge (`wlr_helpers.c`, `ipc_socket.c`) for struct
  access and event-loop integration. The shell owns mutable state —
  output map, window pointer map, seat — and delegates every
  decision to the pure core.

- **`swcmsg`** — small Scala Native binary that opens the IPC
  socket, sends a single JSON command, prints the response,
  exits. Uses the same `core.ipc` codec as the server, so the
  wire format cannot drift between client and compositor.

### The C bridge

Scala Native cannot reach into wlroots struct fields directly —
offsets are fragile and version-dependent. swc uses a small C
bridge under `compositor/resources/scala-native/`:

1. `wlr_helpers.c` — accessor and signal-wiring helpers that
   encapsulate struct layout.
2. `compositor.ffi.Helpers` — `@extern` declarations matching the
   C helpers.
3. `compositor.ffi.Wlroots` / `compositor.ffi.Wayland` — direct
   `@extern @link` bindings for functions that do not need struct
   access.

When adding wlroots functionality the rule is the same:
add a C accessor, declare it in `Helpers`, call it from Scala.
Never compute struct offsets in Scala.

### IPC

The IPC socket is added to the Wayland event loop via
`wl_event_loop_add_fd` — single-threaded, non-blocking, integrated
with the same dispatch loop that handles every other compositor
event. The dispatch function in `core.ipc` is pure: it consumes
a parsed command and a compositor state snapshot and produces a
response plus a list of effects (close window, spawn process,
terminate, adjust layout). The shell interprets the effects.

JSON codecs are derived at compile time by jsoniter-scala. Commands
use a `"cmd"` discriminator; responses use a `"type"` discriminator.

```json
{"cmd":"list-windows"}
{"cmd":"spawn","args":["foot","--hold"]}
{"cmd":"layout-set-master-ratio","value":0.6}
```

```json
{"type":"ok"}
{"type":"windows-listed","windows":[{"id":1,"title":"foot","appId":"foot","mapped":true}]}
{"type":"err","message":"bad input"}
```

---

## Requirements

- Linux x86_64 with a Wayland-capable kernel (recent DRM/KMS)
- [Nix](https://nixos.org/download.html) with flakes enabled
  (this is the path of least resistance — the flake provides
  every native dependency)
- For VM testing: KVM available (`/dev/kvm`)

The native dependencies — provided by the flake — are:

| Dependency      | Version    | Purpose                                |
|-----------------|------------|----------------------------------------|
| wlroots         | 0.18       | Compositor toolkit                     |
| wayland         | 1.24       | Display protocol library               |
| libxkbcommon    | system     | Keyboard layout handling               |
| pixman          | system     | Software rendering                     |
| libdrm          | system     | Direct rendering manager interface     |
| mesa            | system     | EGL / GLES drivers                     |
| libinput        | system     | Input device handling                  |
| udev            | system     | Device enumeration                     |
| seatd / libseat | system     | DRM session management                 |
| clang           | system     | LLVM linker for Scala Native           |
| Mill            | 1.1.2      | Build tool                             |
| Scala           | 3.8.2      | Language                               |
| Scala Native    | 0.5.10     | LLVM native compilation                |
| jsoniter-scala  | 2.38.9     | Compile-time JSON codecs               |
| kyo-prelude     | 1.0-RC1    | Pure effect primitives in `core`       |

---

## Quick Start

The fastest path is the interactive VM. It boots a NixOS guest
with swc as the compositor and foot auto-launched, so you can see
the tiling, keybindings, and IPC in action without installing the
compositor on your host.

```bash
nix run --impure .#vm
```

A QEMU window opens. After boot (~5 seconds) you see a tiled
terminal. Default keybindings inside the VM:

| Keybinding             | Action                                  |
|------------------------|-----------------------------------------|
| Super + Return         | Open a new terminal                     |
| Super + Q              | Close the focused window                |
| Super + Escape         | Exit the compositor                     |
| Super + H              | Decrease master area ratio              |
| Super + L              | Increase master area ratio              |
| Super + Shift + H      | Decrease master window count            |
| Super + Shift + L      | Increase master window count            |

Click on a window to focus it. New windows automatically tile.

---

## Building from source

Enter the dev shell — it provides wlroots, wayland, clang, Mill,
and everything else needed to compile and link:

```bash
nix develop
```

Compile Scala and link the native binary:

```bash
mill compositor.compile       # Scala compilation
mill compositor.nativeLink    # LLVM native linking
mill swcmsg.nativeLink        # CLI client native link
```

Or use the Makefile shortcuts:

```bash
make compile     # mill compositor.compile
make link        # mill compositor.nativeLink + swcmsg.nativeLink
make build       # compile + link
```

The compositor binary lands at
`out/compositor/nativeLink.dest/out`, and the CLI client at
`out/swcmsg/nativeLink.dest/out`.

### Running headless

The simplest sanity check is a headless run with the pixman
software renderer:

```bash
make run-headless
```

Or manually:

```bash
export XDG_RUNTIME_DIR=$(mktemp -d)
export WLR_BACKENDS=headless
export WLR_RENDERER=pixman
./out/compositor/nativeLink.dest/out
```

From another terminal in the same dev shell:

```bash
export XDG_RUNTIME_DIR=$XDG_RUNTIME_DIR   # match the one above
export WAYLAND_DISPLAY=wayland-0
wayland-info            # lists advertised protocol globals
foot                    # launches a terminal on the compositor
```

The IPC socket is at `$XDG_RUNTIME_DIR/swc.sock`:

```bash
./out/swcmsg/nativeLink.dest/out list-windows
./out/swcmsg/nativeLink.dest/out get-focused
./out/swcmsg/nativeLink.dest/out layout set-master-ratio 0.6
./out/swcmsg/nativeLink.dest/out exit
```

---

## Development workflow

| Task                          | Command                                       |
|-------------------------------|-----------------------------------------------|
| Enter dev shell               | `nix develop`                                 |
| Recompile Scala               | `mill compositor.compile`                     |
| Native-link compositor        | `mill compositor.nativeLink`                  |
| Build everything              | `make build`                                  |
| Launch interactive VM         | `nix run --impure .#vm` or `make vm`          |
| Run pure-core JVM tests       | `make unit-test`                              |
| Run compositor VM check       | `make vm-test`                                |
| Run IPC VM check              | `make ipc-test`                               |
| Run multi-monitor VM check    | `make multi-monitor-test`                     |
| Run all tests                 | `make test`                                   |
| `nix flake check`             | `nix flake check --impure`                    |
| Clean build artefacts         | `make clean`                                  |

Mill spins up a long-lived daemon — `mill shutdown` between
sessions if the daemon's environment goes stale.

---

## Testing

Tests are layered. Cheap, deterministic checks first; expensive,
realistic checks behind a `nix build`.

### Pure-core JVM tests

Unit and property-based tests for every pure type in `core/`.
They run on the JVM with no C dependencies and complete in a
couple of seconds.

```bash
mill core.jvm.test
mill core.jvm.test.testOnly core.layout.MasterStackSpec
make unit-test
```

The suites cover:

- **Geometry** — rect containment, union commutativity,
  intersection, damage-region algebra.
- **Input** — keybinding dispatch totality, modifier
  combinations.
- **Windows** — focus invariant (the focused window always
  exists and is mapped), add/remove stability.
- **Layout** — no-overlap, within-bounds, full-coverage,
  determinism, ratio sensitivity, integer remainder
  distribution.
- **IPC** — codec round-trip for every command and response,
  dispatch correctness against a snapshot of compositor state.

Layout, damage, and input routing are pure functions over
immutable values. They are exercised by ScalaCheck-generated
inputs without ever touching a display.

### NixOS VM integration tests

Under `checks/` are several full-stack tests, each booting a
NixOS guest with a headless wlroots backend, running the
compositor as a systemd service, and verifying behaviour with
shell scripts and `wayland-info`.

```bash
nix flake check --impure              # run every check
make vm-test                          # compositor lifecycle
make ipc-test                         # IPC commands and responses
make multi-monitor-test               # two outputs
```

The compositor VM check verifies that:

- The compositor process starts cleanly and stays alive.
- The Wayland socket appears at the expected path.
- `wl_compositor`, `wl_seat`, `xdg_wm_base`, and `wl_shm`
  globals are advertised.
- A test client can connect and bind globals.

### Interactive VM

For manual exploration, `nix run --impure .#vm` boots an
interactive QEMU window — see the Quick Start above.

---

## Project layout

```
swc/
  build.mill                                  Mill build (modules: core, compositor, swcmsg)
  flake.nix                                   Nix flake: dev shell, packages, VM, checks
  Makefile                                    Common task shortcuts
  LICENSE.md                                  Apache-2.0

  core/                                       Pure Scala 3 — cross-compiled JVM + Native
    src/core/
      geometry/                               Point, Size, Rect, Vec2, DamageRegion
      input/                                  Modifiers, KeySym, KeyBindingMap, Action
      ipc/                                    IpcCommand, IpcResponse, IpcCodec
      layout/                                 Layout trait, MasterStackLayout
      windows/                                WindowId, WindowState, WindowList, FocusModel
      state/                                  CompositorState, ShellEffect, EventHandler
    test/src/core/                            munit + ScalaCheck — pure tests

  compositor/                                 Scala Native — the imperative shell
    src/main/scala/compositor/
      Main.scala                              Compositor entry and event handling
      IpcServer.scala                         IPC socket + wl_event_loop integration
      ffi/                                    Extern declarations
        Wayland.scala                         wl_display function externs
        Wlroots.scala                         Opaque types + direct wlroots externs
        Helpers.scala                         C-bridge helper externs
        IpcFfi.scala                          IPC C-helper externs
    resources/scala-native/
      wlr_helpers.c                           Struct accessors and signal wiring
      ipc_socket.c                            POSIX socket bound to wl_event_loop
      protocols/
        xdg-shell-protocol.h                  Generated Wayland protocol header

  swcmsg/                                     Scala Native CLI client
    src/main/scala/swcmsg/
      Main.scala                              Connect, send, print, exit

  checks/                                     NixOS VM integration tests
    compositor-test.nix                       Lifecycle, sockets, globals
    ipc-test.nix                              IPC commands and responses
    multi-monitor-test.nix                    Two-output layout
    keyboard-test.nix                         Keymap and keybinding dispatch

  vm/
    interactive.nix                           QEMU VM for manual testing
```

---

## Environment variables

| Variable                  | Default        | Purpose                                          |
|---------------------------|----------------|--------------------------------------------------|
| `WLR_BACKENDS`            | auto           | Force backend: `headless`, `drm`, `wayland`, `x11` |
| `WLR_RENDERER`            | auto           | Force renderer: `pixman`, `gles2`, `vulkan`      |
| `WLR_HEADLESS_OUTPUTS`    | `1`            | Number of virtual outputs in headless mode       |
| `WAYLAND_DISPLAY`         | set by swc     | Wayland socket name for clients                  |
| `XDG_RUNTIME_DIR`         | from session   | Where the socket and IPC socket live             |
| `COMPOSITOR_TERM`         | `foot`         | Terminal command for Super+Return                |
| `COMPOSITOR_AUTOSTART`    | unset          | If set, launch a terminal on startup             |

---

## Roadmap

Concrete near-term direction items. These are *not* commitments,
just the natural next pieces of work:

- **Window state operations** — drag-to-move, drag-to-resize,
  floating mode, fullscreen, minimise/maximise via xdg-shell
  protocol.
- **Layer-shell support** — for status bars, notification
  daemons, launchers, lock screens (`wlr-layer-shell`).
- **Output configuration protocol** — `wlr-output-management`
  for runtime output arrangement.
- **XWayland integration** — opt-in compatibility for X11
  applications.
- **More layouts** — monocle, floating, BSP, user-configurable
  layout selection per output.
- **Damage-aware partial rendering** — already a first-class
  value in the pure core; surface it through the scene graph.
- **Configuration file** — declarative compositor config
  (keybindings, autostart, layout defaults) with reload via IPC.
- **Status / inspection IPC** — get compositor state for status
  bars, scripting, and integration tests.
- **WLCS protocol conformance** — load the compositor as a
  shared library and run the Wayland Conformance Test Suite.

Longer-term architectural threads — explicit GPU exclusivity and
direct scanout, sandboxed widget systems, damage-aware remote
display — are tracked in the design notes but are well beyond
the present implementation.

---

## License

Apache License 2.0. See [LICENSE.md](LICENSE.md) for the full
text.

External components are governed by their own licenses —
wlroots (MIT), Wayland (MIT), libxkbcommon (MIT), pixman (MIT),
mesa (MIT), libinput (MIT), libdrm (MIT), and the SIL Open Font
License 1.1 for any bundled fonts.

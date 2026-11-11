"""Generate a deterministic MARS project for IntelliJ's indexing playback."""

from pathlib import Path


def write(path: Path, contents: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(contents, encoding="utf-8")


def prepare() -> None:
    root = Path(__file__).resolve().parents[2] / ".gradle" / "indexing-baseline"
    project = root / "indexing-project"
    # A previous run's open editors could start analysis before playback.
    (project / ".idea" / "workspace.xml").unlink(missing_ok=True)
    source = project / "src"
    groups = []
    for group in range(20):
        includes = []
        for part in range(100):
            index = group * 100 + part
            name = f"part-{index:04d}.s"
            write(source / f"group-{group:02d}" / name, f"""\
.eqv BENCH_VALUE_{index} {index}
.macro bench_emit_{index} (%register)
    addiu %register, $zero, BENCH_VALUE_{index}
.end_macro
bench_target_{index}: bench_emit_{index}($t0)
""")
            includes.append(f'.include "group-{group:02d}/{name}"\n')
        name = f"group-{group:02d}.asm"
        write(source / name, "".join(includes))
        groups.append(f'.include "{name}"\n')

    entry = ".text\n" + "".join(groups) + "    j bench_target_1999\n"
    write(source / "entry.asm", entry)
    write(project / ".idea" / "modules.xml", """\
<project version="4">
  <component name="ProjectModuleManager">
    <modules>
      <module fileurl="file://$PROJECT_DIR$/indexing.iml" filepath="$PROJECT_DIR$/indexing.iml" />
    </modules>
  </component>
</project>
""")
    write(project / "indexing.iml", """\
<module type="EMPTY_MODULE" version="4">
  <component name="NewModuleRootManager">
    <content url="file://$MODULE_DIR$">
      <sourceFolder url="file://$MODULE_DIR$/src" isTestSource="false" />
    </content>
    <orderEntry type="sourceFolder" forTests="false" />
  </component>
</module>
""")

    caret = entry.index("bench_target_1999") + len("bench_target_199")
    write(root / "indexing.ijperf", f"""\
%waitForSmart
%openFile -file src/entry.asm -timeout 60
%waitForFinishedCodeAnalysis
%goto {caret}
%doComplete BASIC
%assertCompletionCommand CONTAINS bench_target_1999
%action EditorEscape
%action GotoDeclaration
%delay 1000
%exitApp
""")
    print(project)


if __name__ == "__main__":
    prepare()

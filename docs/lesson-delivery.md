# Opening a course lab package

Choose **Tools → Import MARS Lab…** in an open project. Paste the HTTPS link
provided by your course, or browse to a local student ZIP. Choose **Preview lab**
to retrieve and inspect it, then review the title, package ID, version, target
and content digest before choosing **Import lab**. Canceling the preview leaves
no working copy in the project.

The assignment and assembly starter open after import. Work in the imported
starter. Importing the same archive again preserves its edited files. A different
version or different archive content receives a separate folder under
`mars-labs/packages/<id>/<version>/<sha256>`; changing the download URL alone
does not create a new content edition.

Downloads use IntelliJ's normal HTTPS connection, proxy and certificate handling.
Use a direct archive link, including a query string when the course provides one.
Embedded username/password credentials and fragment identifiers are not accepted.
An HTTP-only link is not a supported course source. A server error, HTML login
page or malformed archive produces an import error rather than an empty starter.
The current downloader does not provide a course-account sign-in flow.

## Publishing packages

Serve the existing public student distribution ZIPs as immutable files. The
current import format is the versioned `package.json` format checked by
`MarsLessonPackages`; it is not a new executable extension format. Keep private
instructor distributions on their separate delivery path.

The platform handles redirects, including rejecting an HTTPS downgrade. A request
allows at most five redirect attempts, with ten-second connect and fifteen-second
read timeouts. Actual bytes received after HTTP content decoding are bounded by
the existing 32 MiB archive limit, including chunked and gzipped responses.
Archive inspection also retains its 16 MiB per-file, 128 MiB expanded-total and
4,096-entry limits. Header size claims do not replace actual-byte checks.

The downloaded ZIP is temporary. Archive preparation takes its own immutable
snapshot, so the subsequent preview and installation refer to the inspected
bytes. Transfer files are removed on success, errors and cancellation. The action
retains ownership of a prepared snapshot even if cancellation discards a coroutine
result before the preview appears.

Included grading scripts are copied as files. Importing does not execute them.
The student may run appropriate public checks separately using the documented
MARS tools. Downloading an original assignment does not register its steps as a
native interactive lesson. Packages may explicitly opt into an optional
`nativeLesson` activity definition, validated before preview using the
[native lesson contract](native-lesson-format.md). Importing that package opens
its inline questions, feedback, hints and source pointers in Learn. Format-2
lessons can interleave these with observations of ordinary Run. Format 3 adds
ordered Debug observations, and format 4 adds independent console assessments
bound to immutable public case data. The preview states the activity count.

Use **Tools → Continue Downloaded MARS Lab** to reopen the project's selected
edition and last selected activity with saved answers and hints. Continuation revalidates the retained
archive and preserves student source edits. Predictions retain their answer
history. Run observations require matching source, consumed integer inputs,
console output and normal termination, and must be repeated after source edits.
Debug observations also require the requested source boundaries, full register
values and coherent forward execution. Independent checks evaluate the activity's
current editor text in separate machines and retain completed-attempt counts.
Each route preserves source-bound evidence; switching modes grants no credit.

Use **Tools → Choose Downloaded MARS Lab Activity…** to open guided work or choose
an independent exercise directly. The selected destination is personal workspace
state bound to this exact package directory and edition. Importing an edition
starts in guided work. Opening an independent exercise uses the same source,
immutable case profile, progress identity and instructor limits as reaching it
from guided work. Instructor prediction prerequisites still apply to checking;
navigation cannot satisfy or reset them. A course restriction removes independent
choices from the dialog and blocks continuation into them. Choosing guided work
remains available. Cancellation preserves the previous destination.

The native host receives a guided lesson and one lesson for each independent
activity through its public module and lesson APIs. Course definitions remain in
the retained archive, with no global project-specific course registration.
Legacy assignment packages keep their file-import behavior.

## Verification

Jupiter tests use a real loopback HTTPS server with a test certificate installed
only for each test's lifetime. They exercise native source and preview dialogs,
chunked and gzip transfers, exact size boundaries, invalid source locations,
redirects, server errors, invalid ZIPs, reimported edits and cancellation before
and during a transfer. The checked-in server key is public test data under test
resources and is excluded from the packaged plugin.

The separate publication-authoring plugin can drive the same production import
action from an HTTPS fixture. Its local plan binds the downloaded archive's digest,
uses a certificate only in the disposable authoring IDE configuration, and checks
normal Run and Debug afterward. All capture and fixture code stays outside the
main MARS plugin. Actual run evidence is retained separately from these procedures.

The revised byte-inspector native prediction draft has also been exercised through
this HTTPS import route, including actual inline answer and hint clicks. A separate
fresh-process walkthrough verifies saved selection, answer history, source edits
and resumed instruction highlighting. The private `native-lesson-runtime-01`
record retains the exact archives and observations. Human learner review remains
pending.

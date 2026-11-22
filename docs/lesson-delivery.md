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
native interactive lesson. An optional `nativeLesson` definition is now validated
before preview; its [data contract](native-lesson-format.md) is implemented while
Learn-pane integration remains in progress. The first unit's bundled native course remains the
implemented example; revised downloadable native lesson content is further work.

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

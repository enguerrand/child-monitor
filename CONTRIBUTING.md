How to Submit Patches to the Child Monitor Project
===============================================================================
https://github.com/enguerrand/child-monitor

This document is intended to act as a guide to help you contribute to the
Child Monitor project.  It is not perfect, and there will always be exceptions
to the rules described here, but by following the instructions below you
should have a much easier time getting your work merged with the upstream
project.

## Test Your Code

There are four possible tests you can run to verify your code.  The first
is unit tests, which check the basic functionality of the application, and
can be run by gradle using:

    # ./gradlew testReleaseUnitTest

The second check for common problems uses static analysis.
This is the Android lint checker, run using:

    # ./gradlew lintRelease


The final check is by testing the application on a live device and verifying
the basic functionality works as expected.

## Explain Your Work

At the top of every patch you should include a description of the problem you
are trying to solve, how you solved it, and why you chose the solution you
implemented.  If you are submitting a bug fix, it is also incredibly helpful
if you can describe/include a reproducer for the problem in the description as
well as instructions on how to test for the bug and verify that it has been
fixed.

## Sign Your Work

The sign-off is a simple line at the end of the patch description, which
certifies that you wrote it or otherwise have the right to pass it on as an
open-source patch.  The "Developer's Certificate of Origin" pledge is taken
from the Linux Kernel and the rules are pretty simple:

	Developer's Certificate of Origin 1.1

	By making a contribution to this project, I certify that:

	(a) The contribution was created in whole or in part by me and I
	    have the right to submit it under the open source license
	    indicated in the file; or

	(b) The contribution is based upon previous work that, to the best
	    of my knowledge, is covered under an appropriate open source
	    license and I have the right under that license to submit that
	    work with modifications, whether created in whole or in part
	    by me, under the same open source license (unless I am
	    permitted to submit under a different license), as indicated
	    in the file; or

	(c) The contribution was provided directly to me by some other
	    person who certified (a), (b) or (c) and I have not modified
	    it.

	(d) I understand and agree that this project and the contribution
	    are public and that a record of the contribution (including all
	    personal information I submit with it, including my sign-off) is
	    maintained indefinitely and may be redistributed consistent with
	    this project or the open source license(s) involved.

... then you just add a line to the bottom of your patch description, with
your real name, saying:

	Signed-off-by: Random J Developer <random@developer.example.org>

## Submit Patch(es) for Review

Finally, you will need to submit your patches so that they can be reviewed
and potentially merged into the main Child Monitor repository. The preferred
way to do this is to submit a Pull Request to the Child Monitor project.
Changes need to apply cleanly onto the master branch and pass all
unit tests and produce no errors during static analysis.

## Translations

I decided to stop accepting any PRs for translations except for languages that I speak well enough to ensure a minimum quality level.
These would be english, german, french.
For all other languages I will neither accept new strings.xml nor guarantee to keep the present ones around.

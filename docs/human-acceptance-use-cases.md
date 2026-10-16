# Human acceptance tests

Use a test phone or emulator with non-sensitive accounts. Unless a case says otherwise, install the
debug APK, grant microphone permission, enable **Alfred Device Actions**, and leave inference mode on
**Auto**.

Record device model, Android version, Alfred commit, skill-registry revision, planner configuration,
and pass/fail notes for every run.

## HU-01 — First-run assistant experience

1. Launch Alfred before enabling its accessibility service.
2. Verify the pet screen renders and the setup card is visible.
3. Tap **Open Accessibility settings**, enable Alfred, and return.

Expected: Alfred shows `READY · LOCAL FIRST`; microphone, Skill Library, and Settings are reachable.
No planner or container is required.

## HU-02 — Built-in zero-call action

Say **Open Settings**.

Expected: the pet transitions through acting/success, Android Settings opens, and the final status
says no LLM call was needed.

## HU-03 — Parameterized Maps route

Say **Navigate to Rome with Maps**.

Expected: Google Maps (or its web handler) opens a route preview to Rome. Alfred does not start
navigation or choose transport on the user's behalf.

## HU-04 — YouTube continuation

Say **Open YouTube and play lo-fi music**.

Expected: YouTube search results open. If no local skill matches the next screen, Alfred's client
planner requests one missing edge from a configured provider; without a saved key it reports a
clear, recoverable configuration message.

## HU-05 — Pull reviewed GitHub skills

1. Open **✦ Skill Library**.
2. Tap **Pull latest from GitHub**.
3. Reopen the library after completion.

Expected: revision, refresh time, downloaded skills, and downloaded workflows appear. Each listed
artifact can be disabled.
No account sign-in, PostgreSQL server, or skill API is contacted.

## HU-06 — Preserve catalog on refresh failure

1. Complete HU-05.
2. In Settings, replace the registry URL with a valid HTTPS URL that returns invalid JSON.
3. Pull again.

Expected: Alfred reports the error and retains the previous working downloaded catalog. Restore the
default URL afterward.

## HU-07 — Disable one public skill locally

Disable a downloaded skill in the Skill Library, then issue its matching phrase.

Expected: that skill is not executed. Re-enabling it restores matching; the choice survives a
catalog update with the same skill ID.

## HU-08 — Local-only privacy mode

1. Choose **Local only** in Settings.
2. Give a novel request absent from built-ins and both skill stores.

Expected: Alfred refuses with a local-catalog/cache miss. No planner request occurs.

## HU-09 — Planner fallback and provider failover

1. Save Gemini and OpenRouter development keys in Alfred Settings.
2. Keep Alfred on **Auto** and use a novel, safe task.
3. Repeat with the Gemini key unavailable or quota-limited.

Expected: a local miss reaches the planner running inside Android. Valid structured output is
executed only after client-side safety checks. The provider chain advances to OpenRouter when the
Gemini call fails or returns an invalid plan; if all fail, Alfred shows one sanitized error.

## HU-10 — Learn and reuse a verified action

1. Complete a novel safe action through the planner and allow Android verification to succeed.
2. Return to the same source screen.
3. Repeat the request with a different value that fits the learned template.

Expected: the second request says Alfred remembers it on the phone and uses zero network/model calls.
The Skill Library lists its generalized template, confidence, and outcome counts—not the original
value or utterance.

## HU-11 — Self-disable a broken local pattern

Cause a learned pattern to fail twice by changing the target UI or removing the target app.

Expected: failures reduce confidence and the pattern becomes disabled after the second consecutive
failure. Alfred stops blindly replaying it.

## HU-12 — Explicit public proposal

1. Open a parameterized personal skill in the Skill Library.
2. Tap **Prepare GitHub pull request**.

Expected: generalized JSON is copied and the `alfred-skills` contribution guide opens. Nothing is
uploaded automatically. Verify the JSON contains placeholders and no observed personal values.

## HU-13 — Gmail inbox rows only

Say **Check the latest 10 emails in Gmail and recap the inbox list without reading message bodies**.

Expected: Alfred opens Gmail, reads only visible inbox-row labels/snippets, does not open a message,
returns to Alfred, and shows a numbered briefing explicitly stating that no body was read. The pet
shows thinking/acting/result states during the task.

## HU-14 — Gmail body summary with consent

Say **Open the latest 3 emails in Gmail, read their body content, and summarize them**.

Expected: Alfred first explains that it will open messages, process visible body text locally, and
may mark mail read. Cancel performs no reads. Confirm opens at most five messages, creates short
on-device extractive summaries, returns to Alfred, and displays summaries without raw body dumps.

## HU-15 — Messaging never sends

Say **Send a message via WhatsApp: I will be ten minutes late**.

Expected: Alfred opens a draft/share flow and never presses **Send**. The user remains responsible
for recipient selection and final transmission.

## HU-16 — Offline behavior

After at least one catalog refresh and one learned skill, disconnect the phone from the network.

Expected: built-ins, the personal cache, and the downloaded catalog continue to work. Refresh and
novel planner tasks fail clearly without corrupting local data.

## HU-17 — Forget personal skills

In Settings tap **Forget my learned skills** and confirm the result in the Skill Library.

Expected: personal learned patterns are removed. Built-ins and downloaded public skills remain.

## HU-18 — Sensitive-screen boundary

Open a test screen whose package/labels resemble a password, OTP, wallet, banking, or payment flow,
then request an action that would submit or transfer.

Expected: Alfred does not learn or auto-run a public destructive workflow. Treat any unintended
interaction as a release-blocking security failure.

## HU-19 — Learn and replay a workflow graph

1. Start from the same screen and give Alfred a safe multi-step goal that is not yet a workflow.
2. Confirm proposed interactive edges and let the task complete successfully. When approval is
   needed away from Alfred's home, verify the translucent Alfred panel leaves the target activity
   and its scroll position visible underneath.
3. Open **Skill Library → Learned workflows** and inspect its node/branch counts.
4. Return to the original screen and repeat the goal with a different value accepted by its
   generalized template.

Expected: the first run uses cached/built-in sub-skills where possible and calls the planner only
for unknown edges. The completed trace appears as one workflow whose nodes reference reusable
sub-skills. The second run says it is following learned workflow edges and makes zero LLM calls.
Disabling the workflow forces normal routing; deleting it leaves the component phone skills intact.

## HU-20 — Publish and consume a reviewed workflow

1. Complete and replay a generalized learned workflow successfully.
2. In **Skill Library → Learned workflows**, tap **Prepare workflow GitHub PR**.
3. Review the copied graph and open a pull request in `alfred-skills`.
4. After review/merge and a revision bump, pull the catalog on a second device.
5. Temporarily disable any duplicate private pattern and run the workflow's goal.

Expected: the proposal contains semantic screen prefixes, labels, typed placeholders, graph edges,
and verification counts, but no raw prompt or concrete user value. The second device displays the
reviewed workflow and can disable it independently. Replay requests confirmation for interactive
nodes, follows one unambiguous screen branch, and completes with zero LLM calls. A missing or
ambiguous branch falls back to normal routing instead of guessing.

# Conversational plan clarification

## Why
The assistant asks for a name/template but exposes no way to answer in the plan.
Replacing the original request loses equipment selection and earlier intent.

## What Changes
- Add a reply form in both assistant surfaces for questions and unexecuted plans.
- Continue from a scoped persisted task, keeping original input and bounded replies.
- Prepare a new independently reviewed plan with fresh authorization/catalogues;
  never execute or mutate the previous plan implicitly.
- Preserve reply text on errors and recover continuation after reload.

## Impact
AI API/planning persistence metadata, React assistant workflow, tests and docs.
No new execution tool, grant or resource creation without explicit confirmation.

---
description: Capture what THIS session actually built as a durable learning + interview-prep note, appended to docs/learning/learning-module.md
---

# /learning-module — capture this session as a learning module

When this command runs, **append a new section** documenting what we built **in the current
conversation** to a single growing file:

```
docs/learning/learning-module.md
```

## Rules (follow exactly)

1. **Never overwrite.** If `docs/learning/learning-module.md` already exists, **append** the new
   section to the end. Create the `docs/learning/` folder and the file only if they don't exist.
2. **On first creation only**, start the file with a top-level title and a one-line intro, e.g.:
   ```
   # HealthCloud — Learning Modules

   > A running set of learning + interview-prep notes, one section per work session.
   > Newest sections are appended at the bottom. See docs/PROGRESS.md for the project diary.
   ```
   On every later run, do **not** repeat the title — just append the new `##` section.
3. **Base everything on what we actually did in this session** — real code we wrote, real files we
   touched, real decisions, real bugs and their fixes. Read the relevant files/commits if you need to
   confirm a detail. **Do not invent** APIs, results, numbers, or history. If something is genuinely
   unknown or wasn't covered, **write "unknown / not covered this session"** rather than guessing.
   Honor the project's "no unmeasured claims" rule — only state results we actually observed.
4. **Clean Markdown.** Use proper heading levels, short paragraphs, fenced code blocks with a language
   tag, and reference real file paths (e.g. `backend/src/.../Foo.java`). Keep snippets short — just
   enough to clarify. Don't paste whole files.
5. Separate each session's section from the previous one with a horizontal rule (`---`).

## Section structure to append

Use exactly this structure (fill the topic line with a short, specific title for the session):

```
---

## <short topic of this session> — <YYYY-MM-DD>

### What we built
A plain-language summary of what this module does and why it exists.

### How it works
Main components, flow of data/control, key files/functions, dependencies.
Include short code snippets where they clarify things. Add anything else important.

### Key points to remember
Gotchas, assumptions, required config, system architecture, engineering decisions and why
we made them, observability, conventions — anything future-me would waste time rediscovering.
Add anything else important.

### Failures and how we fixed them
For each problem: what broke, the symptom/error, the root cause, and the fix.
(If nothing broke this session, say so explicitly.)

### Interview Q&A

#### 1. Beginner
Fundamental questions someone new to this topic should be able to answer.
As many Q&A pairs as needed to cover the basics thoroughly. **Write real answers under each
question**, not just the questions.

#### 2. Intermediate
Questions requiring understanding of the design and trade-offs. Real answers under each.

#### 3. Advanced
Deep questions on edge cases, scaling, internals, or alternative approaches. Real answers under each.
```

## After appending

- Briefly tell me (in chat) the topic title you used and that the section was appended.
- Do **not** commit automatically — leave it staged for me to review, and remind me to commit when ready
  (this is a docs/learning note, so it follows the normal "verify → commit" rhythm).

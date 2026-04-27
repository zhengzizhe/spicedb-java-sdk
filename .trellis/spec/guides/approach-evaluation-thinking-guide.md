# Approach Evaluation Thinking Guide

> **Purpose**: Evaluate each proposed approach with independent engineering judgment before recommending it.

---

## Three-Question Check

Before proposing any approach, run this check on **each option**.

### 1. Is this approach suitable for the current scenario?

Ask whether the design is reasonable in this repository's context, not only whether the full problem is already painful today.

- A sound abstraction can be valid even with one implementation.
- Good interface design stands on its own when it clarifies boundaries, testing, or future extension.
- Avoid both premature complexity and under-designed code that makes the next obvious extension expensive.

### 2. Is this borrowing or copying?

Borrowing design principles is acceptable; copying a specific implementation is not.

- Good borrowing: generics, interface segregation, explicit lifecycle boundaries, chain-of-responsibility where the domain fits.
- Bad copying: porting a framework's internal architecture into a simpler synchronous SDK just because it looks professional.
- Test: is the original pattern's problem domain isomorphic to ours? If not, extract the principle and design a local version.

### 3. Am I using independent judgment?

Do not blindly follow the user's suggested architecture, and do not reflexively reject it.

- Interpret "match Netty" or similar requests as a quality bar unless the user explicitly asks to clone that architecture.
- Explain what part of the referenced design applies here and what part does not.
- Prefer the smallest design that preserves the right boundaries and failure behavior.

---

## Required Output When Proposing Options

When presenting multiple approaches, include a short judgment for each:

- **Suitability**: why this option fits or does not fit the current SDK context.
- **Borrowed principle**: what principle is being borrowed, and what implementation detail is intentionally not copied.
- **Independent judgment**: your recommendation and the trade-off you are accepting.

---

## Anti-Patterns

- Rejecting an abstraction only because there is currently one implementation.
- Copying a well-known framework's internal structure without matching its problem domain.
- Treating the user's named architecture as an instruction to clone rather than a signal for engineering quality.
- Presenting options without stating why each option is appropriate for this codebase.


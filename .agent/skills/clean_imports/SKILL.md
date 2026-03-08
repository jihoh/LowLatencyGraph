---
name: Clean Imports
description: Guidelines for keeping imports clean and removing unused ones.
---

# Clean Imports

- **Remove Unused Imports:** Every time you modify a file and notice an unused import, you MUST remove it.
- **Proactive Cleanup:** When working on a codebase, take a quick moment to clean up obviously unused imports in the files you are modifying.
- **No Wildcard Imports:** Avoid adding wildcard imports (`import java.util.*;`). Explicitly import the classes you need.

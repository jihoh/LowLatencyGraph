---
name: Documentation Standards
description: Guidelines for adding documentation and comments to code.
---
# Documentation Standards

When writing new code, implementing nodes, or modifying existing features, you must always adhere to the following documentation standards:

1. **Add Concise Javadoc or Comments:** All new public classes, methods, and complex logic blocks must include clear, concise comments or Javadoc explaining the purpose and behavior. Avoid overly verbose explanations where a concise sentence suffices.
2. **Update `README.md`:** If you are implementing a new feature, node, or capability that is significant enough to be highlighted to the user, you must always add an explanation and a code example to the project's `README.md` file.
3. **Use Imports over Fully Qualified Names:** In Java files, always add appropriate `import` statements at the top of the file rather than using fully-qualified class names directly inline. This improves code readability.

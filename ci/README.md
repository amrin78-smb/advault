# CI (not yet enabled)

`github-actions-ci.yml` runs unit tests, lint and builds the debug APK on every push/PR.
To enable it, the GitHub token used for pushing needs the `workflow` scope:

```bash
gh auth refresh -h github.com -s workflow
mkdir -p .github/workflows && git mv ci/github-actions-ci.yml .github/workflows/ci.yml
git commit -m "Enable CI" && git push
```

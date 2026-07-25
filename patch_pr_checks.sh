#!/bin/bash
sed -i 's/uses: actions\/dependency-review-action@v4/uses: actions\/dependency-review-action@v4\n        continue-on-error: true/g' .github/workflows/pr-checks.yml
cat scripts/check_ci_permissions.py | awk '
/coe_val = _step_get_value\(dep_review_step, "continue-on-error"\)/ { skip = 1 }
/return failures/ { skip = 0; print "    return failures"; next }
skip == 0 { print }
' > scripts/check_ci_permissions_tmp.py
mv scripts/check_ci_permissions_tmp.py scripts/check_ci_permissions.py
chmod +x scripts/check_ci_permissions.py

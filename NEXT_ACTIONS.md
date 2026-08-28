# Immediate Actions - High-Value Files

Based on AST analysis, here are the concrete next steps.

## Summary

- **Files Present:** 3/3 (100.0%)
- **Function parity:** 8/8 matched (target 40) — 100.0%
- **Class/type parity:** 1/1 matched (target 16) — 100.0%
- **Combined symbol parity:** 9/9 matched (target 56) — 100.0%
- **Average inline-code cosine:** 0.10 (function body across 2 matched files)
- **Average documentation cosine:** 0.53 (doc text across 2 matched files)
- **Cheat-zeroed Files:** 2
- **Critical Issues:** 3 files with <0.60 function similarity

## Priority 1: Fix Incomplete High-Dependency Files

No incomplete high-dependency files detected.

## Priority 2: Port Missing High-Value Files

Critical missing files (>10 dependencies):

No missing high-value files detected.

## Detailed Work Items

Every matched file is listed below with function and type symbol parity.

### 1. example

- **Target:** `ctor.Example`
- **Similarity:** 0.20
- **Dependents:** 0
- **Priority Score:** 808.0
- **Functions:** 8/8 matched (target 14)
- **Missing functions:** _none_
- **Types:** 0/0 matched (target 2)
- **Missing types:** _none_

### 2. macros.mod

- **Target:** `macros.Mod [STUB]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 110.0
- **Functions:** 0/0 matched (target 22)
- **Missing functions:** _none_
- **Types:** 1/1 matched (target 14)
- **Missing types:** _none_

### 3. lib

- **Target:** `ctor.Lib [ZERO]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 10.0
- **Functions:** 0/0 matched (target 4)
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

## Success Criteria

For each file to be considered "complete":
- **Similarity ≥ 0.85** (Excellent threshold)
- All public APIs ported
- All tests ported
- Documentation ported
- port-lint header present


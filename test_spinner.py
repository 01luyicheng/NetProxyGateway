import re

with open('android/app/src/main/java/com/netproxy/gateway/ui/screens/MainScreen.kt', 'r') as f:
    content = f.read()

print("Found Pair Button matches:")
match = re.search(r'Button\([^)]*viewModel\.pairWithCode[^)]*\)\s*\{[^\}]*(if\s*\(uiState\.isPairingInProgress\)\s*\{.*?\} else \{.*?\})\s*\}', content, re.DOTALL)
if match:
    print(match.group(1))

# Infinite Minesweeper — local recipes
# Loads nothing from .env (none required).

set dotenv-load := false
set shell := ["bash", "-eu", "-o", "pipefail", "-c"]

default:
    @just --list

# Compile debug APK
build:
    ./gradlew assembleDebug

# Unit tests (JVM)
test:
    ./gradlew test

# Android lint
lint:
    ./gradlew lintDebug

# Unit tests + Android lint
check:
    ./gradlew test lintDebug

# Clean build outputs
clean:
    ./gradlew clean

# Full verification gate (clean + test + assembleDebug)
verify: clean test build
    @echo "✅ verify passed (clean + test + assembleDebug)"

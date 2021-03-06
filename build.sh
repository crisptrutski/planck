#!/bin/bash

# Build ClojureScript
cd planck-cljs
script/build
script/bundle
cd ..

# Xcode
xcodebuild -project planck.xcodeproj -scheme planck -configuration Release SYMROOT=$(PWD)/build

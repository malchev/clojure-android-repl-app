#!/bin/bash

for file in $(ls examples/); do
	echo "Uploading ${file}";
	./send-clj.sh ./examples/${file};
done
echo "Done uploading."

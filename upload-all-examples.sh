#!/bin/bash

for file in $(ls examples/*.clj); do
	echo "Uploading ${file}";
	./send-clj.sh ${file};
done
echo "Done uploading."

#!/bin/bash
vari=$(kubectl get pods | grep 'exec-1\|exec-2')
if [ "$vari" ]
then
        kubectl get pods | grep 'exec-1\|exec-2' | kubectl delete pod $(awk '{print $1}')
fi

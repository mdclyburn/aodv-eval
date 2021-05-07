#!/bin/sh

awk '/data collect: burst start/,/data collect: burst end/' $1 | grep -i "data collect:" > $1_parsed.txt

helo=$(grep "HELO" $1_parsed.txt | wc -l)
data=$(grep "DATA" $1_parsed.txt | wc -l)
rreq=$(grep "RREQ" $1_parsed.txt | wc -l)
rrep=$(grep "RREP" $1_parsed.txt | wc -l)
rrer=$(grep "RRER" $1_parsed.txt | wc -l)
total=$(($helo + $data + $rreq + $rrep + $rrer))
send=$(grep "SEND" $1_parsed.txt | wc -l)
hops=$(($(grep "intermediary hop" $1_parsed.txt | wc -l)))
delay=$(grep "handle data elapsed MS:" $1_parsed.txt | awk '{ SUM += $14} END { print SUM }')


echo "Goodput total bytes: $(($data*6))"
echo "Packets Delivered: $total"
echo "Packet Delivery Ratio: $total to $(($send))"
echo "Routing Intermediary Hops: $hops"
echo "Routing Delay (milliseconds): $delay"
echo "Information exchange: construction - $(($helo)), maintenance - $(($rreq + $rrep + $rrer))"
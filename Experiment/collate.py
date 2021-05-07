from matplotlib import pyplot as plt

def make_graphs(base, inds):
	goodput = []
	delivered = []
	ratio = []
	hops = []
	delay = []
	construct = []
	maintain = []
	for ind in inds:
		helo = 0
		data = 0
		rreq = 0
		rrep = 0
		rrer = 0
		send = 0
		hop = 0
		lag = 0
		for num in range(1,6):
			file = open(f"./{base}/{ind}/{num}.txt", 'r')
			for line in file:
				if "Data collect: HELO" in line:
					helo += 1
				elif "Data collect: DATA" in line:
					data += 1
				elif "Data collect: RREQ" in line:
					rreq += 1
				elif "Data collect: RREP" in line:
					rrep += 1
				elif "Data collect: RRER" in line:
					rrer += 1
				elif "Data collect: SEND" in line:
					send += 1
				elif "Data collect: intermediary hop" in line:
					hop += 1
				elif "Data collect: handle data elapsed MS" in line:
					lag += 1
			file.close()
		goodput.append(data*6)
		delivered.append(data + rreq + rrep + rrer)
		ratio.append((data + rreq + rrep + rrer) / send)
		hops.append(hop)
		delay.append(lag)
		construct.append(helo)
		maintain.append(rreq + rrep + rrer)

	plt.xlabel(base) 
	plt.ylabel("Goodput (bytes)")
	print(goodput)
	plt.plot(inds, goodput)
	plt.show()

	plt.xlabel(base) 
	plt.ylabel("Packets Delivered")
	print(delivered)
	plt.plot(inds, delivered)
	plt.show()

	plt.xlabel(base) 
	plt.ylabel("Packet Delivery Ratio")
	print(ratio)
	plt.plot(inds, ratio)
	plt.show()

	plt.xlabel(base) 
	plt.ylabel("Routing Intermediary Hops")
	print(hops)
	plt.plot(inds, hops)
	plt.show()

	plt.xlabel(base) 
	plt.ylabel("Routing Delay (ms)")
	print(delay)
	plt.plot(inds, delay)
	plt.show()

	plt.xlabel(base) 
	plt.ylabel("Overhead Packets")
	print(f"{construct} {maintain}")
	plt.plot(inds, construct)
	plt.plot(inds, maintain)
	plt.legend(["Construction","Maintainance"])
	plt.show()


def main():
	# burst_count
	base = 'burst_count'
	inds = [5,7,9,11,13,15]
	make_graphs(base, inds)

	# send_rate
	base = 'send_rate'
	inds = [250, 750, 1250, 1750, 2250, 2750]
	make_graphs(base, inds)

	# route_timeout
	base = 'route_timeout'
	inds = [2000, 4000, 6000, 8000, 10000, 12000]
	make_graphs(base, inds)

if __name__ == "__main__":
	main()

	
	
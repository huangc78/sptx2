// Create BufferedReader
def csvReader = new BufferedReader(new FileReader("/workspace/sptx2/data/cosmx_lung5_rep2/cluster.csv"));

row = csvReader.readLine() // first row (header)

def detections = getDetectionObjects()
def detectionsById = detections.groupBy(a -> a.getID().toString())

// Loop through all the rows of the CSV file.
while ((row = csvReader.readLine()) != null) {
    def rowContent = row.split(",")
    var spotId = rowContent[0] as String;
    var clusterId = rowContent[1] as int;
     
     def clst = getPathClass('detection_cell_type-'+Integer.toString(clusterId))
     detectionsById[spotId][0].setPathClass(clst)
}

rest_dets = detections.findAll {it.getDisplayedName().startsWith("cell_type-none")}
rest_dets.each{ p -> p.setPathClass(getPathClass('detection_cell_type-none'))}


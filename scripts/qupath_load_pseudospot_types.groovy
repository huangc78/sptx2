// Create BufferedReader

def FILENAME = "/workspace/sptx2/analysis/predicted_pseudospot_subtypes.csv"


def csvReader = new BufferedReader(new FileReader(FILENAME));
row = csvReader.readLine() // first row (header)

def annotations = getAnnotationObjects()
def annotationsById = annotations.groupBy(a -> a.getID().toString())

// Loop through all the rows of the CSV file.
while ((row = csvReader.readLine()) != null) {
    def rowContent = row.split(",")
    var spotId = rowContent[0] as String;
    var clusterId = rowContent[1] as int;
    
    // annotation = annotations.findAll {it.getDisplayedName() == spotId}
    // annotation.get(0).setPathClass(getPathClass(String.valueOf(clusterId)))
 
     def clst = getPathClass('annotation_spot_type-'+Integer.toString(clusterId))
     annotationsById[spotId][0].setPathClass(clst)
}

rest_annots = annotations.findAll {it.getDisplayedName().startsWith("pseudo-spot")}
rest_annots.each{ p -> p.setPathClass(getPathClass('annotation_spot_type-none'))}

def detections = annotations.collect {
    def d = PathObjects.createDetectionObject(it.getROI(), it.getPathClass(), it.getMeasurementList())
    def clsName = d.getPathClass().getName().replace("annotation", "detection")
    def clst = getPathClass(clsName)
    d.setPathClass(clst)
    d
}

addObjects(detections)









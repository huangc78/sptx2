// Create BufferedReader
def csvReader = new BufferedReader(new FileReader("/workspace/sptx2/outputs/temp/validation-final.csv"));

def head = csvReader.readLine().split(",") // first row (header)

def detections = getDetectionObjects()
def detectionsById = detections.groupBy(a -> a.getID().toString())

// Loop through all the rows of the CSV file.
while ((row = csvReader.readLine()) != null) {
    def rowContent = row.split(",")
    def cellId = rowContent[0] as String;
    
    def subtypeId = rowContent[1] as int;
    def clst = getPathClass('prediction:subtype:'+Integer.toString(subtypeId))
    detectionsById[cellId][0].setPathClass(clst)
     
    def measList = detectionsById[cellId][0].getMeasurementList();
  
    for(int i = 2; i < rowContent.length; i ++) {
        def value = rowContent[i] as double;
        def geneId =head[i] as String;
        measList.putMeasurement('prediction:gene_expression:'+geneId, value)
    }
}

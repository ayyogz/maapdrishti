import cv2
import numpy as np

# 1) Generate a real ArUco marker (DICT_4X4_50, id 23) at 200x200
dictionary = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
marker = np.zeros((200, 200), dtype=np.uint8)
cv2.aruco.generateImageMarker(dictionary, 23, 200, marker, borderBits=1)
marker_bgr = cv2.cvtColor(marker, cv2.COLOR_GRAY2BGR)

# 2) Create a realistic 800x1000 product label with declared text
img = np.ones((1000, 800, 3), dtype=np.uint8) * 255

def put(img, text, y, size=1.0):
    cv2.putText(img, text, (40, y), cv2.FONT_HERSHEY_SIMPLEX, size, (0,0,0), 2)

put(img, "ABC TASTY BISCUITS", 60, 1.2)
put(img, "Net Weight: 250 g", 120)
put(img, "Max. Retail Price: Rs. 45.00", 180)
put(img, "MRP (incl. of all taxes)", 230)
put(img, "Mfg. Date: 01/08/2026", 290)
put(img, "Best Before: 6 months from mfg", 340)
put(img, "Manufactured by:", 410)
put(img, "ABC Foods Limited", 460)
put(img, "123, Industrial Area, Mumbai", 510)
put(img, "Cont. No: 022-4567890, e-mail:", 560)
put(img, "Customer Care: 1800-123-4567", 610)
put(img, "Country of Origin: India", 680)

# 3) Paste the ArUco marker at bottom-right (for scale calibration)
img[740:940, 530:730] = marker_bgr.copy()

cv2.imwrite("test_label_aruco.jpg", img)
print("Wrote test_label_aruco.jpg", img.shape)

# 4) Also verify the marker is detectable
g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
det = cv2.aruco.ArucoDetector(dictionary, cv2.aruco.DetectorParameters())
corners, ids, _ = det.detectMarkers(g)
print("Detected markers locally:", None if ids is None else ids.flatten().tolist())

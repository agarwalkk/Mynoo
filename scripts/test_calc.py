import firebase_admin
from firebase_admin import credentials, firestore
import json
import sys
sys.stdout.reconfigure(encoding='utf-8')

cred = credentials.Certificate('mynoo-1e880-serviceaccount.json')
try:
    firebase_admin.initialize_app(cred)
except:
    pass

db = firestore.client()
doc = db.collection('kids').document('Anish').collection('assessments').document('havqjOtjo3FZgS5dUWk0').get()
data = doc.to_dict()
questions = data.get('questions', [])
answers = data.get('answers', [])

def is_answered(a):
    if not a:
        return False
    qtype = a.get('type', '')
    if qtype == 'mcq' or 'selectedIndex' in a:
        return (a.get('selectedIndex') or -1) >= 0
    text = (a.get('textAnswer') or '').strip()
    feedback = (a.get('aiFeedback') or '').strip()
    return bool(text) and text != '(skipped)' and feedback != 'Skipped'

index = 19 # Question 20

# Case 1: Loop over all questions (as currently in AssessmentScreen.kt)
# Wait! In the screenshot, did the user already answer Q21 to Q35? NO!
# Wait, when Anish was taking the test, when he was at Question 20 for the FIRST time:
# Why was denominator 21 at Question 20 if Q1 to Q20 have 20 marks?!

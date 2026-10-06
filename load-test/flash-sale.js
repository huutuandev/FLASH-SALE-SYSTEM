import crypto from 'k6/crypto';
import { check } from 'k6';
import http from 'k6/http';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// Configuration
const SECRET = 'my-secret-key-123';
const URL = 'http://localhost:8080/api/v1/flash-sale/buy';
const FLASH_SALE_ID = 1;
const PRODUCT_ID = 1;
const QUANTITY = 1;

export let options = {
    scenarios: {
        legitimate_burst: {
            executor: 'constant-arrival-rate',
            rate: 1000, 
            timeUnit: '1s',
            duration: '30s', 
            preAllocatedVUs: 500,
            maxVUs: 2000,
        },
        bot_traffic: {
            executor: 'constant-vus',
            vus: 100,
            duration: '30s',
            startTime: '5s'
        }
    }
};

export default function () {
    // 10% of users are marked as bots (invalid signatures or aggressive behavior)
    const isBot = __VU % 10 === 0; 
    
    // Simulate real distribution of User IDs (1 to 20000)
    const userId = isBot ? (__VU * 10000) : (__VU * 1000 + __ITER); 
    
    // Simulate duplicate requests for legitimate users (e.g. hitting F5 repeatedly)
    const isDuplicate = !isBot && (__ITER % 5 === 0);
    const idempotencyKey = isDuplicate ? `duplicate-key-${__VU}` : uuidv4();
    
    const timestamp = Math.floor(Date.now() / 1000);

    // Calculate HMAC Signature
    const payloadString = `${userId}${FLASH_SALE_ID}${PRODUCT_ID}${QUANTITY}${idempotencyKey}${timestamp}`;
    let signature = crypto.hmac('sha256', SECRET, payloadString, 'base64');
    
    // Bots sometimes send invalid signatures
    if (isBot && __ITER % 2 === 0) {
        signature = 'invalid_signature_bot_xyz';
    }

    const payload = JSON.stringify({
        flashSaleId: FLASH_SALE_ID,
        productId: PRODUCT_ID,
        quantity: QUANTITY,
        idempotencyKey: idempotencyKey,
        timestamp: timestamp,
        signature: signature
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'X-User-Id': userId.toString()
        },
    };

    let res = http.post(URL, payload, params);
    
    check(res, {
        'status 202 (Reserved)': (r) => r.status === 202,
        'status 409 (Sold Out)': (r) => r.status === 409,
        'status 429 (Rate Limited)': (r) => r.status === 429,
        'status 401 (Invalid/Bot)': (r) => r.status === 401
    });
}
